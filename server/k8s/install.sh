#!/usr/bin/env bash
# Brings up the whole platform on a local kind cluster:
#   operators (ingress-nginx, metrics-server, cert-manager, CNPG + Barman plugin, Strimzi),
#   observability (kube-prometheus-stack, ECK Elasticsearch/Kibana, Fluent Bit),
#   service images, and the `banking` Helm chart.
# Re-runnable. Usage (from server/):
#   bash k8s/install.sh                 full profile (~10 GB RAM)
#   PROFILE=demo bash k8s/install.sh    ~5 GB: 1 Kafka broker, 1 Postgres, no backups, no EFK logging
set -euo pipefail
PROFILE="${PROFILE:-full}"
case "$PROFILE" in full|demo) ;; *) echo "PROFILE must be full or demo"; exit 1 ;; esac
full() { [ "$PROFILE" = full ]; }

cd "$(dirname "$0")/.."
K8S=k8s
NS=banking
SERVICES="auth-service ledger-service transfer-service fraud-service notification-service gateway-service"

INGRESS_NGINX=controller-v1.15.1
METRICS_SERVER=v0.9.0
CERT_MANAGER=v1.21.3
CNPG=1.30.1
BARMAN_PLUGIN=v0.15.1
STRIMZI=1.3.0
KPS=92.14.0
ECK=3.5.0
FLUENT_BIT=0.58.3

step() { printf '\n=== %s ===\n' "$*"; }

step "kind cluster"
kind get clusters | grep -qx banking || kind create cluster --config $K8S/kind-cluster.yaml
kubectl config use-context kind-banking
docker exec banking-control-plane sysctl -w fs.inotify.max_user_instances=8192 fs.inotify.max_user_watches=524288 2>/dev/null || true
# Cap the whole cluster's CPU so ~40 JVMs starting at once can't freeze the host (persists across restarts)
KIND_CPUS="${KIND_CPUS:-$(( $(nproc) > 3 ? $(nproc) - 2 : 1 ))}"
docker update --cpus "$KIND_CPUS" banking-control-plane >/dev/null

step "preload images that stall when pulled from inside kind"
# Seen repeatedly: containerd in the kind node hangs mid-download on these registries while the
# host's docker pulls the same image in seconds. Pull on the host (cached for reinstalls) and load.
PRELOAD="ghcr.io/cloudnative-pg/cloudnative-pg:$CNPG
ghcr.io/cloudnative-pg/postgresql:18.6-system-trixie
quay.io/debezium/connect:2.7"
if full; then PRELOAD="$PRELOAD
ghcr.io/cloudnative-pg/plugin-barman-cloud:$BARMAN_PLUGIN
ghcr.io/cloudnative-pg/plugin-barman-cloud-sidecar:$BARMAN_PLUGIN"; fi
# Not `kind load`: it imports --all-platforms and fails on multi-arch images, whose other platforms
# a containerd-backed docker never downloaded ("content digest ... not found").
ARCH=$(docker version -f '{{.Server.Arch}}')
for img in $PRELOAD; do
  docker pull -q "$img" >/dev/null
  docker save "$img" | docker exec -i banking-control-plane \
    ctr -n k8s.io images import --platform "linux/$ARCH" --digests --snapshotter=overlayfs - >/dev/null
done

step "ingress-nginx, metrics-server"
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/$INGRESS_NGINX/deploy/static/provider/kind/deploy.yaml
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/download/$METRICS_SERVER/components.yaml
# kind kubelets use self-signed certs
kubectl -n kube-system patch deploy metrics-server --type=json \
  -p '[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]' 2>/dev/null || true

if full; then
step "cert-manager (required by the Barman Cloud plugin)"
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/$CERT_MANAGER/cert-manager.yaml
kubectl -n cert-manager rollout status deploy --timeout=5m
fi

step "CloudNativePG + Barman Cloud plugin"
kubectl apply --server-side -f https://github.com/cloudnative-pg/cloudnative-pg/releases/download/v$CNPG/cnpg-$CNPG.yaml
kubectl -n cnpg-system rollout status deploy/cnpg-controller-manager --timeout=5m
if full; then
  kubectl apply -f https://github.com/cloudnative-pg/plugin-barman-cloud/releases/download/$BARMAN_PLUGIN/manifest.yaml
  kubectl -n cnpg-system rollout status deploy/barman-cloud --timeout=5m
fi

step "Strimzi"
helm upgrade --install strimzi oci://quay.io/strimzi-helm/strimzi-kafka-operator --version $STRIMZI \
  -n strimzi --create-namespace --set watchAnyNamespace=true --wait

step "kube-prometheus-stack"
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts >/dev/null
helm repo add fluent https://fluent.github.io/helm-charts >/dev/null
helm repo update >/dev/null
KPS_VALUES=(-f $K8S/values/kube-prometheus-stack.yaml)
full || KPS_VALUES+=(-f $K8S/values/kube-prometheus-stack-demo.yaml)
helm upgrade --install monitoring prometheus-community/kube-prometheus-stack --version $KPS \
  -n monitoring --create-namespace "${KPS_VALUES[@]}" --wait --timeout 10m

if full; then
step "ECK Elasticsearch + Kibana, Fluent Bit"
kubectl create -f https://download.elastic.co/downloads/eck/$ECK/crds.yaml 2>/dev/null || \
  kubectl replace -f https://download.elastic.co/downloads/eck/$ECK/crds.yaml
kubectl apply -f https://download.elastic.co/downloads/eck/$ECK/operator.yaml
kubectl create namespace logging --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -f $K8S/logging/elastic.yaml
until kubectl -n logging get secret banking-es-es-elastic-user >/dev/null 2>&1; do sleep 5; done
helm upgrade --install fluent-bit fluent/fluent-bit --version $FLUENT_BIT -n logging -f $K8S/values/fluent-bit.yaml
fi

step "service images"
# unique tag per run, so helm upgrade rolls the pods whenever images are rebuilt
TAG=$(date +%Y%m%d%H%M%S)
for s in $SERVICES; do
  docker build -q --build-arg MODULE=$s -t banking/$s:$TAG .
done
# shellcheck disable=SC2046
kind load docker-image --name banking $(printf "banking/%s:$TAG " $SERVICES)

step "banking chart"
kubectl create namespace $NS --dry-run=client -o yaml | kubectl apply -f -
[ -f docker/keys/jwt-private.pem ] || bash docker/keys/generate-keys.sh
kubectl -n $NS create secret generic jwt-keys \
  --from-file=docker/keys/jwt-private.pem --from-file=docker/keys/jwt-public.pem \
  --dry-run=client -o yaml | kubectl apply -f -
# --wait-for-jobs: returns once the Debezium connector and backup bucket are in place
CHART_VALUES=()
full || CHART_VALUES=(-f $K8S/values/banking-demo.yaml)
helm upgrade --install banking $K8S/helm/banking -n $NS "${CHART_VALUES[@]}" --set image.tag=$TAG --wait --wait-for-jobs --timeout 20m

# First base backup (PITR needs one); runs now that the bucket hook has finished
full && kubectl -n $NS apply -f - <<EOF
apiVersion: postgresql.cnpg.io/v1
kind: Backup
metadata:
  name: banking-pg-initial
spec:
  cluster: {name: banking-pg}
  method: plugin
  pluginConfiguration: {name: barman-cloud.cloudnative-pg.io}
EOF

step "done"
cat <<EOF
API (via ingress -> gateway):  http://localhost
Smoke test:                    GATEWAY_URL=http://localhost PSQL="kubectl -n $NS exec -i \$(kubectl -n $NS get pod -l cnpg.io/cluster=banking-pg,cnpg.io/instanceRole=primary -o name) -c postgres -- psql" bash scripts/smoke.sh
Grafana:                       kubectl -n monitoring port-forward svc/monitoring-grafana 3000:80   (admin/admin)
EOF
if full; then cat <<EOF
Kibana:                        kubectl -n logging port-forward svc/banking-kb-kb-http 5601         (see k8s/logging/elastic.yaml)
Backups:                       kubectl -n $NS get backups
EOF
else echo "Logs (demo profile, no EFK): kubectl -n $NS logs deploy/<service> -f"; fi
