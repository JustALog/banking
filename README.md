# Banking Platform — Core Platform, Services & Kubernetes (Round 3)

Distributed banking & payment platform per `Architect_diagram.drawio`.
- **Round 1**: Core money-movement path: Auth/Customer → Transfer (saga + outbox + Debezium CDC) → Fraud (stub) → Ledger (sole balance owner) → Partner Bank call (Resilience4j).
- **Round 2**: **API Gateway** (Spring Cloud Gateway, JWT verification, Idempotency-Key enforcement), **Notification Service** (`notify_db`, consumes completed/failed events, mocks Email/SMS), **Fraud Service with persistence & real velocity rules** (`fraud_db`), and **Ledger Reversal Compensation** for partner bank failures after posting.
- **Round 3**: **Kubernetes** (kind, Helm, Strimzi 3-broker KRaft Kafka, CloudNativePG primary+standby, NGINX Ingress, HPA/PDB/NetworkPolicy), **Backup** (Barman Cloud plugin → S3, daily + continuous WAL, PITR), **Observability** (kube-prometheus-stack + Grafana dashboard, Fluent Bit → ECK Elasticsearch/Kibana).
- **Test console**: Vite + vanilla-JS UI in `client/` for auth, accounts, transfers, notifications, scripted failure scenarios and a live API log.

A React SPA is still on the roadmap; the current `client/` is intentionally framework-free.

## Layout

```
client/
  index.html            Vite entry point
  DESIGN.md             visual tokens and component notes
  src/main.js           login/register, accounts, transfers, notifications, scenarios
  src/api.js            gateway fetch wrapper, JWT handling, live API log
  src/scenarios.js      automated failure-path checks
  src/style.css         console styling
  vite.config.js        dev server on :5173, proxies API paths to the gateway

web/                    customer + admin banking website (React, :5174)
  src/gateway.js        calls the gateway: JWT header, session storage, error messages
  src/components.jsx    shell, receipt, shared hooks
  src/pages/            public.jsx, user.jsx, admin.jsx

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
- Node.js `^20.19.0` or `>=22.12.0`, plus npm, for the test console
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

For the browser test console, see [Test console (client/)](#test-console-client).

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
| Logs | Fluent Bit (namespace `banking` only) → ECK Elasticsearch → Kibana, index `banking-*` | `kubectl -n logging port-forward svc/banking-kb-kb-http 5601`, then create data view `banking-*` (time field `@timestamp`) |

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

## Website (web/)

The customer-facing banking site: React 19 + React Router 7 on Vite, talking to the gateway only.

- **Public:** landing page, sign-in, and registration (opens a first VND account automatically).
- **Customer (`/app`):** overview with total balance per currency and account cards; accounts list and opening new ones; account detail with statement and the account id to share for receiving money; transfer with review step and a per-transfer `Idempotency-Key`; live transfer receipt that polls until the saga settles; transaction history with status filter; notifications; profile.
- **Admin (`/admin`, `ROLE_ADMIN` only):** customers with search and lock/unlock; all accounts with owner and deposit; all transfers with status filter.

```bash
cd web
npm install
npm run dev     # http://localhost:5174 (GATEWAY_URL overrides http://localhost:8080)
npm test        # formatter unit tests (node --test)
```

The session (JWT + profile) is kept in `sessionStorage`: a refresh keeps you signed in, closing the tab signs you out.

## Test console (client/)

A lightweight Vite + vanilla-JS console for exercising the system through the gateway. It keeps the JWT in memory, shows every request/response in a live API log, and has four tabs for every user plus three admin tabs:

- **Tài khoản** — open accounts, list balances, inspect ledger entries. There is no deposit API; the tab prints the `psql` command to seed a balance.
- **Chuyển tiền** — submit a transfer with an `Idempotency-Key`, then watch the saga poll to `COMPLETED` or `FAILED`.
- **Thông báo** — notifications persisted by notification-service.
- **Quản trị: Khách hàng / Tài khoản / Giao dịch** — shown only to `ROLE_ADMIN`: list all customers and lock/unlock them, list all accounts and deposit into them, list all transfers. Sign in as `admin@banking.local` / `admin12345` (set by `BANKING_ADMIN_EMAIL` / `BANKING_ADMIN_PASSWORD` in docker compose; unset means no admin is created).
- **Kịch bản kiểm thử** — one-click checks for USER getting 403 on admin APIs, missing JWT, blocked internal routes, missing/replayed idempotency keys, fraud threshold, insufficient funds and velocity rejection. The partner-timeout/reversal case is manual because it needs a service restart.

Prerequisites: Node.js `^20.19.0` or `>=22.12.0`, plus npm. Start the backend first (`bash server/scripts/demo.sh`), then:

```bash
cd client
npm install
npm run dev     # http://localhost:5173
```

Vite proxies `/auth`, `/accounts`, `/transfers` and `/notifications` to the gateway, so the browser never calls the services directly and no CORS setup is needed. Override the target with `GATEWAY_URL=http://host:8080 npm run dev`.

`npm run build` emits static assets to `client/dist/`.

## Roadmap

1. React SPA: done in `web/`. `client/` stays as the test console for now.
