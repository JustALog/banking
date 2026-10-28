# Banking Platform — Core Platform, Services & Kubernetes (Round 3)

Distributed banking & payment platform per `Architect_diagram.drawio`.
- **Round 1**: Core money-movement path: Auth/Customer → Transfer (saga + outbox + Debezium CDC) → Fraud (stub) → Ledger (sole balance owner) → Partner Bank call (Resilience4j).
- **Round 2**: **API Gateway** (Spring Cloud Gateway, JWT verification, Idempotency-Key enforcement), **Notification Service** (`notify_db`, consumes completed/failed events, mocks Email/SMS), **Fraud Service with persistence & real velocity rules** (`fraud_db`), and **Ledger Reversal Compensation** for partner bank failures after posting.

- **Round 3**: **Kubernetes** (kind, Helm, Strimzi 3-broker KRaft Kafka, CloudNativePG primary+standby, NGINX Ingress, HPA/PDB/NetworkPolicy), **Backup** (Barman Cloud plugin → S3, daily + continuous WAL, PITR), **Observability** (kube-prometheus-stack + Grafana dashboard, Fluent Bit → ECK Elasticsearch/Kibana).

React SPA is still to come.

## Layout

```
server/
  common-events/        Kafka topic names + event records (shared contract)
  gateway-service/      :8080  Spring Cloud Gateway, JWT resource server, route filter
  auth-service/         :8081  register/login, RS256 JWT issuance (customer_db)
  ledger-service/       :8082  accounts REST, double-entry posting, balance owner, reversal compensation (ledger_db)
  transfer-service/     :8083  saga orchestrator + transactional outbox (transfer_db)
  fraud-service/        :8084  threshold & sliding-window velocity rules, audit log (fraud_db)
  notification-service/ :8085  event consumer, notification persistence, mock email/SMS (notify_db)
  docker/               docker-compose infra: postgres (5 databases), kafka (KRaft), debezium connect,
                        wiremock partner mock, kafka-ui (http://localhost:8090)
  scripts/smoke.sh      end-to-end happy path, idempotency, notification, and gateway routing check
  Dockerfile            one image recipe for all services (--build-arg MODULE=<service>)
  k8s/
    install.sh          kind cluster + operators + observability + images + `banking` chart (re-runnable)
    helm/banking/       one chart: all 6 services (looped from values.yaml), Kafka, Postgres, Debezium,
                        partner mock, backups, ServiceMonitors, Grafana dashboard
    values/             values for third-party charts (kube-prometheus-stack, fluent-bit)
    logging/            ECK Elasticsearch + Kibana
    backup/             PITR restore example
```

Full API list grouped by service (vendor), with request/response examples: [API_LIST.md](API_LIST.md).

## Prerequisites

- JDK 21+, Docker Desktop (or any docker compose v2)
- bash + curl (the smoke script uses python3 or node for JSON parsing; either works)

## Quickstart

Everything (infra + 6 services) runs in docker compose, memory-capped (hard cap ~4 GB):

```bash
cd server
bash scripts/demo.sh        # keys → docker compose up --build --wait → Debezium connector → smoke test
```

First run builds 6 images (several minutes); later runs reuse them. Useful extras:

```bash
docker stats --no-stream                                            # per-container memory
docker compose -f docker/docker-compose.yml --profile tools up -d   # Kafka UI on http://localhost:8090
BANKING_PARTNER_EXECUTE_PATH=/partner-slow/v1/transfers \
  docker compose -f docker/docker-compose.yml up -d transfer-service  # partner-timeout + reversal demo
docker compose -f docker/docker-compose.yml down                    # stop (add -v to wipe data)
```

To hack on one service from the IDE instead, `docker compose stop <service>` and run
`mvn -pl <service> spring-boot:run` — defaults in `application.yml` point at localhost; Postgres is published on host port **5433**, so also set e.g. `BANKING_DB_URL=jdbc:postgresql://localhost:5433/ledger_db`.
Kafka Connect REST is on host port **8086**.

Swagger UI per service: `http://localhost:808{1..5}/swagger-ui.html`.

## How the money moves & recovers

1. `POST /transfers` with `Idempotency-Key` → API Gateway validates JWT and ensures `Idempotency-Key` header exists.
2. Transfer-service validates both accounts with ledger-service, then commits **one atomic transaction**:
   `transfer` row (PENDING) + `outbox_event` row (`transfer.requested`). Returns 202.
3. **Debezium Outbox Event Router** tails `transfer_db` WAL → publishes `transfer.requested` (key = source accountId).
4. **Fraud-service** evaluates the request:
   - Single transfer amount threshold check
   - Frequency velocity check (max transfers in sliding time window)
   - Volume velocity check (aggregate amount in sliding time window)
   - Records full evaluation in `fraud_db` and publishes `fraud.decided` (`APPROVED` or `REJECTED`).
5. **Ledger-service** consumes `fraud.decided`:
   - Locks both accounts (`SELECT … FOR UPDATE`, sorted UUID order to prevent deadlocks).
   - Posts debit/credit with `UNIQUE(transfer_id, account_id, direction)` guard.
   - Publishes `ledger.posted`. (If it cannot post — insufficient funds, unknown account — publishes `ledger.rejected`; transfer-service fails the saga and writes `transfer.failed` to its outbox.)
6. **Transfer-service** consumes `ledger.posted` → calls the partner mock under **a 3s HTTP read timeout + Resilience4j CircuitBreaker**:
   - **Success**: Transfer marked COMPLETED → writes `transfer.completed` to outbox.
   - **Timeout / Failure**: Transfer marked FAILED → writes `transfer.failed` to outbox.
7. **Compensation (Ledger Reversal)**:
   - When `transfer.failed` is published after posting, **ledger-service consumes it**.
   - Identifies original debit and credit entries, locks accounts, and executes **compensating reversal entries**:
     refunds the source account (`CREDIT`) and debits the destination account (`DEBIT`).
8. **Notification-service** consumes `transfer.completed` and `transfer.failed`:
   - Persists notification records in `notify_db`.
   - Simulates email/SMS delivery to the customer.

## Verifying the failure paths

| Scenario | How | Expected |
|---|---|---|
| Gateway missing Idempotency-Key | omit `Idempotency-Key` header on `POST /transfers` | HTTP 400 Bad Request directly from Gateway |
| Idempotency replay | re-send same `Idempotency-Key` | same transfer id, HTTP 200, no duplicate saga |
| Fraud threshold reject | transfer amount > threshold | FAILED / FRAUD_REJECTED, balances unchanged |
| Fraud velocity reject | submit > 5 transfers within 60s | FAILED / FRAUD_REJECTED from the 6th on; the rule detail (`VELOCITY_COUNT`, `TOO_MANY_TRANSFERS_IN_WINDOW: 6`) is only in `fraud_db.fraud_evaluation`, not exposed to the customer |
| Insufficient funds | transfer more than balance | FAILED / INSUFFICIENT_FUNDS, balances unchanged |
| Partner timeout + Reversal | set `banking.partner.execute-path=/partner-slow/v1/transfers` (5s delay vs 3s read timeout) | FAILED / PARTNER_TIMEOUT; ledger-service consumes failure and **automatically reverses posted balances** |
| Poison message | malformed event on a consumed topic | 3 retries → `transfer.dlq` |

## Design notes

- **Money is `BIGINT` minor units** everywhere; no floats.
- Producers: `acks=all`, `enable.idempotence=true`; consumers read `read_committed`.
- Every service is an OAuth2 Resource Server validating the RS256 public key.
- Ledger is the sole owner of account balances; `@Version` optimistic locking plus pessimistic row locks during posting.
- Transactional outbox pattern prevents dual-write issues.
- Gateway acts as the central ingress point: JWT verification and the mandatory `Idempotency-Key` header. No rate limiting yet (Spring Cloud Gateway's limiter needs Redis).
- Every Kafka consumer retries a failing record 3 times (1s apart), then dead-letters it to `transfer.dlq`.
- One traceId follows a transfer through every service: W3C `traceparent` over HTTP and Kafka headers
  (Spring Kafka observation), and across the outbox hop via the `outbox_event.traceparent` column, which
  Debezium copies into the Kafka header. Kibana: filter `app.traceId : "<id>"`. Rows in different DBs are
  correlated by `transfer_id` (present in transfer, ledger_entry, fraud_evaluation, notification).

## Kubernetes (kind)

Needs docker, kind, kubectl, helm. RAM budget for the whole kind node: **~9 GB steady, ≤10 GB** with gateway/fraud fully scaled (3 Kafka brokers, Postgres primary+standby, Elasticsearch/Kibana, Prometheus), or ~5 GB with `PROFILE=demo`. Every pod has a memory limit and the Spring services run with a fixed 192 MB heap.

```bash
cd server
bash k8s/install.sh                  # full profile: ~15 min on first run; prints URLs and the k8s smoke-test command
PROFILE=demo bash k8s/install.sh     # low-memory profile, ~5 GB (see below)
```

**`PROFILE=demo`** keeps the same architecture (Strimzi, CNPG, Debezium, Ingress, HPA/PDB/NetworkPolicy,
Prometheus + Grafana dashboard) with fewer copies: 1 Kafka broker (RF=1), 1 Postgres (no standby),
small fixed JVM heaps, no backups (skips cert-manager/Barman/SeaweedFS), no EFK (use `kubectl logs`),
trimmed Prometheus (no node-exporter/default rules). Overrides live in `k8s/values/banking-demo.yaml`
and `k8s/values/kube-prometheus-stack-demo.yaml`. Switching profiles on an existing cluster is not
supported (Strimzi can't shrink a KRaft quorum in place): `kind delete cluster --name banking` first.

| Piece | What runs | Notes |
|---|---|---|
| Ingress | ingress-nginx → `gateway-service` | `http://localhost` (kind maps host :80) |
| Services | 1 replica each; HPA on CPU scales gateway to 2 and fraud to 3 (others capped at 1 to stay in the RAM budget), PDB `maxUnavailable: 1` | JSON logs (`json` profile), read-only root FS, non-root |
| NetworkPolicy | each service accepts traffic only from its listed callers (`allowFrom` in values) + Prometheus | gateway only from the ingress-nginx namespace |
| Kafka | Strimzi KRaft, 3 dual-role nodes, RF=3, `min.insync.replicas=2`, topics as `KafkaTopic` CRs | Kafka Exporter for consumer lag |
| Postgres | CNPG `banking-pg`, primary + standby; one role + `<role>_db` per service (`Database` CRs), random passwords in `pg-<role>` secrets | `wal_level=logical` for Debezium |
| CDC | Debezium Connect (plain Deployment) + post-install Job that upserts the outbox connector | DB password read via `${env:...}` config provider |
| Backup | Barman Cloud plugin: continuous WAL archive + daily base backup (and one at install) to SeaweedFS S3, 7-day retention | PITR: `k8s/backup/restore-pitr.yaml` |
| Metrics | kube-prometheus-stack; ServiceMonitor per service; "Banking Platform" Grafana dashboard (req/s, p95, 5xx, consumer lag, heap, DB pool) | `kubectl -n monitoring port-forward svc/monitoring-grafana 3000:80` |
| Logs | Fluent Bit (namespace `banking` only) → ECK Elasticsearch → Kibana, index `banking-*` | `kubectl -n logging port-forward svc/banking-kb-kb-http 5601` |

Known limits:
- SeaweedFS stands in for MinIO (MinIO stopped publishing images) and lives in the same cluster: fine for a demo, not a real off-site backup.
- CDC across a Postgres failover relies on PG17+ failover slots: the `debezium` slot is pre-created with
  `failover=true` (Debezium 2.7 cannot), CNPG `synchronizeLogicalDecoding` copies it to the standby,
  a 10s Debezium heartbeat (`debezium_heartbeat` table) keeps it advancing so it is sync-ready, and a
  janitor sidecar drops the stale slot the old primary keeps when it rejoins. Right after a failover
  the new standby needs ~2-3 min to re-sync the slot; a second failover inside that window can still
  miss outbox rows. Recovery (safe: every consumer is idempotent) re-emits the request of stuck sagas:
  `INSERT INTO outbox_event (id, aggregatetype, aggregateid, type, payload, created_at, traceparent)
  SELECT gen_random_uuid(), o.aggregatetype, o.aggregateid, o.type, o.payload, now(), o.traceparent
  FROM outbox_event o JOIN transfer t ON o.payload->>'transferId' = t.id::text
  WHERE t.status = 'PENDING' AND o.aggregatetype = 'transfer.requested';` (on `transfer_db`).
- ingress-nginx is retired upstream (2026); the Ingress object is standard, so swapping controllers is a values change.

## Roadmap

1. React SPA in `client/`.

## Test console (client/)

A small web page for exercising the system through the gateway, with a live API log. Start the backend first (`bash server/scripts/demo.sh`), then:

```bash
cd client
npm install
npm run dev     # http://localhost:5173 (GATEWAY_URL overrides the default http://localhost:8080)
```
