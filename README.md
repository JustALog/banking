# Banking Platform

**English** · [Tiếng Việt](README.vi.md)

A distributed banking and payment platform: six Spring Boot services behind an API gateway, an event-driven money-transfer saga on Kafka, and a React website. It runs locally with Docker Compose or on Kubernetes (kind).

The design is in [Architect_diagram.drawio](Architect_diagram.drawio). The HTTP API is in [API_LIST.md](API_LIST.md) (Vietnamese).

## Highlights

- **Transfer saga with a transactional outbox.** Transfer state and the `transfer.requested` event are committed in one DB transaction. Debezium CDC publishes the event to Kafka, so there is no dual write.
- **Ledger is the only owner of balances.** It does double-entry posting with row locks taken in a fixed order, and it reverses automatically if the partner bank fails after posting.
- **Fraud rules** are an amount threshold plus sliding-window count and volume velocity. Every evaluation is stored.
- **Gateway** verifies the JWT and requires an `Idempotency-Key` on transfers.
- **Resilience:** the partner call has a 3 s timeout and a circuit breaker. Failing Kafka records are retried 3 times, then sent to `transfer.dlq`.
- **Operations:** the Kubernetes deployment has HA Kafka and Postgres, PITR backups, Prometheus/Grafana metrics and EFK logs with one trace id per transfer.

## Repository layout

```
client/                  React 19 + React Router 7 + Vite website (talks to the gateway only)
server/
  common-events/         Kafka topic names and event records (shared contract)
  gateway-service/ :8080 Spring Cloud Gateway: JWT resource server, Idempotency-Key filter
  auth-service/    :8081 register/login, RS256 JWT issuance        (customer_db)
  ledger-service/  :8082 accounts, double-entry posting, reversal  (ledger_db)
  transfer-service/:8083 saga orchestrator + outbox                (transfer_db)
  fraud-service/   :8084 threshold + velocity rules, audit log     (fraud_db)
  notification-service/ :8085 consumes outcomes, mock email/SMS    (notify_db)
  docker/                Compose stack: Postgres, Kafka (KRaft), Debezium Connect, WireMock partner, Kafka UI
  k8s/                   kind cluster, install.sh, Helm chart, third-party chart values, logging, backup
  scripts/               demo.sh (bring up + smoke test), smoke.sh (end-to-end checks)
```

## Quickstart (Docker Compose)

Requires JDK 21+, Docker with Compose v2, bash, curl, and python3 or node (used by the smoke script).

```bash
cd server
bash scripts/demo.sh   # generate keys → compose up --build --wait → Debezium connector → smoke test
```

The first run builds six images and takes several minutes. The stack is memory-capped at about 4 GB.
Later runs: `bash scripts/demo.sh --fast` reuses images and skips the smoke test.

### Test accounts by role

`bash scripts/demo.sh` creates these accounts: it starts auth-service with `BANKING_SEED_DEMO_ACCOUNTS=true` (off by default in compose), which seeds one account per role (password `Test@1234`). `demo.sh` logs in to each and prints its `roles` claim.

| Email | Role (JWT claim) |
|---|---|
| `user@test.local` | `ROLE_USER` |
| `staff@test.local` | `ROLE_STAFF` |
| `admin@test.local` | `ROLE_ADMIN` |

Log in via `POST /auth/login`. `/auth/register` always creates `USER`. No endpoint checks `STAFF`/`ADMIN` yet; add `hasRole(...)` in a service's `SecurityConfig` to use them. Demo only: never enable the flag in production.

| What | Where |
|---|---|
| API (via gateway) | http://localhost:8080 |
| Swagger UI per service | http://localhost:808{1..5}/swagger-ui.html |
| Kafka UI (`--profile tools`) | http://localhost:8090 |
| Postgres (host port) | localhost:5433 |
| Kafka Connect REST | http://localhost:8086 |

```bash
docker compose -f docker/docker-compose.yml --profile tools up -d    # start Kafka UI
docker compose -f docker/docker-compose.yml down                     # stop (add -v to wipe data)
docker stats --no-stream                                             # per-container memory
```

To work on one service from your IDE, run `docker compose stop <service>`, then `mvn -pl <service> spring-boot:run`. Defaults in `application.yml` point at localhost. Point the DB at the published port, for example `BANKING_DB_URL=jdbc:postgresql://localhost:5433/ledger_db`.

Backend tests: `cd server && bash docker/keys/generate-keys.sh && mvn verify`. This is what CI runs ([backend-ci.yml](.github/workflows/backend-ci.yml)).

## Website

```bash
cd client
npm install
npm run dev    # http://localhost:5173; set GATEWAY_URL to change the default http://localhost:8080
npm test       # formatting/validation unit tests (node --test)
```

Start the backend first. The visual design is specified in [client/DESIGN.md](client/DESIGN.md).

- **Public:** landing page, sign-in, registration (opens a first VND account).
- **Customer (`/app`):** balances per currency, accounts, statements, a 3-step transfer (details, review, live receipt), history, notifications, profile.
- **Admin (`/admin`, `ROLE_ADMIN`):** customers (search, lock/unlock), all accounts (deposit), all transfers. With Compose, sign in as `admin@test.local` / `Test@1234` (see [Test accounts by role](#test-accounts-by-role)).

The session (JWT and profile) is kept in `sessionStorage`. A refresh keeps you signed in, and closing the tab signs you out.

## How a transfer flows

1. `POST /transfers` with an `Idempotency-Key`. The gateway checks the JWT and the header.
2. Transfer-service validates both accounts with the ledger. In one transaction it writes the `transfer` row (PENDING) and a `transfer.requested` outbox row, then returns 202.
3. Debezium reads the outbox from the WAL and publishes `transfer.requested` to Kafka.
4. Fraud-service applies its rules, stores the evaluation, and publishes `fraud.decided` (APPROVED or REJECTED).
5. Ledger-service locks both accounts in sorted UUID order and posts the debit and credit. A unique `(transfer_id, account_id, direction)` constraint makes this idempotent. It publishes `ledger.posted`, or `ledger.rejected` for insufficient funds or an unknown account.
6. Transfer-service calls the partner bank. Success marks the transfer COMPLETED and emits `transfer.completed`. A timeout or failure marks it FAILED and emits `transfer.failed`.
7. If a failure comes after posting, ledger-service consumes `transfer.failed` and writes reversing entries: it refunds the source and debits the destination.
8. Notification-service stores a notification and simulates email/SMS for completed and failed transfers.

### Failure scenarios

| Scenario | Trigger | Expected result |
|---|---|---|
| Missing key | no `Idempotency-Key` on `POST /transfers` | 400 from the gateway |
| Replay | same `Idempotency-Key` again | same transfer id, 200, no second saga |
| Fraud threshold | amount above threshold | FAILED / FRAUD_REJECTED, balances unchanged |
| Fraud velocity | more than 5 transfers in 60 s | FAILED / FRAUD_REJECTED from the 6th. The rule detail is only in `fraud_db.fraud_evaluation`. |
| Insufficient funds | amount above balance | FAILED / INSUFFICIENT_FUNDS, balances unchanged |
| Partner timeout | `BANKING_PARTNER_EXECUTE_PATH=/partner-slow/v1/transfers` (5 s delay vs 3 s timeout) | FAILED / PARTNER_TIMEOUT, then automatic ledger reversal |
| Poison message | malformed event on a consumed topic | 3 retries, then `transfer.dlq` |

To try the partner timeout:

```bash
BANKING_PARTNER_EXECUTE_PATH=/partner-slow/v1/transfers \
  docker compose -f docker/docker-compose.yml up -d transfer-service
```

## Design decisions

- **Money** is `BIGINT` in minor units. There are no floats anywhere.
- **Kafka:** producers use `acks=all` with idempotence. Consumers read `read_committed`.
- **Auth:** every service is an OAuth2 resource server that validates the RS256 public key.
- **Concurrency:** the ledger combines `@Version` optimistic locking with pessimistic row locks while posting.
- **Tracing:** one trace id follows a transfer across HTTP, Kafka and the outbox hop (the `outbox_event.traceparent` column, which Debezium copies into a Kafka header). In Kibana, filter on `app.traceId : "<id>"`. Rows in different databases correlate by `transfer_id`.
- **Not implemented:** gateway rate limiting, because Spring Cloud Gateway's limiter needs Redis.

## Kubernetes (kind)

Requires docker, kind, kubectl and helm.

```bash
cd server
bash k8s/install.sh                  # full profile, ~15 min first run, about 9 GB RAM (10 GB at most)
PROFILE=demo bash k8s/install.sh     # low-memory profile, about 5 GB
```

`install.sh` can be re-run and prints the URLs and the smoke-test command at the end. Switching profiles on an existing cluster is not supported, because Strimzi cannot shrink a KRaft quorum. Run `kind delete cluster --name banking` first.

`PROFILE=demo` keeps the same architecture with fewer copies. It has 1 Kafka broker, 1 Postgres with no standby, no backups, no EFK stack, and a trimmed Prometheus. Overrides are in `k8s/values/banking-demo.yaml` and `k8s/values/kube-prometheus-stack-demo.yaml`.

| Piece | What runs |
|---|---|
| Ingress | ingress-nginx → gateway at http://localhost |
| Services | 1 replica each. HPA scales gateway to 2 and fraud to 3. PDB `maxUnavailable: 1`. JSON logs, read-only root FS, non-root. |
| NetworkPolicy | Each service accepts only its listed callers and Prometheus. The gateway accepts only the ingress namespace. |
| Kafka | Strimzi KRaft, 3 brokers, RF=3, `min.insync.replicas=2`, topics as `KafkaTopic` CRs |
| Postgres | CloudNativePG primary + standby, one role and database per service, `wal_level=logical` |
| CDC | Debezium Connect plus a job that upserts the outbox connector |
| Backup | Barman Cloud plugin: continuous WAL and daily base backup to SeaweedFS S3, 7-day retention. PITR example: `k8s/backup/restore-pitr.yaml` |
| Metrics | kube-prometheus-stack, a ServiceMonitor per service, "Banking Platform" Grafana dashboard. `kubectl -n monitoring port-forward svc/monitoring-grafana 3000:80` |
| Logs | Fluent Bit → ECK Elasticsearch → Kibana (index `banking-*`). `kubectl -n logging port-forward svc/banking-kb-kb-http 5601` |

### Known limits

- SeaweedFS stands in for MinIO and runs inside the same cluster. That is fine for a demo but not a real off-site backup.
- ingress-nginx is retired upstream. The Ingress object is standard, so swapping controllers is a values change.
- **CDC across a Postgres failover** relies on PG17+ failover slots.
  - The `debezium` slot is pre-created with `failover=true`, CNPG copies it to the standby, a 10 s heartbeat keeps it advancing, and a janitor sidecar drops the stale slot on the old primary.
  - After a failover the new standby needs about 2-3 minutes to re-sync the slot. A second failover inside that window can miss outbox rows.
  - Recovery is safe because every consumer is idempotent. On `transfer_db`, re-emit the request of stuck sagas:

    ```sql
    INSERT INTO outbox_event (id, aggregatetype, aggregateid, type, payload, created_at, traceparent)
    SELECT gen_random_uuid(), o.aggregatetype, o.aggregateid, o.type, o.payload, now(), o.traceparent
    FROM outbox_event o JOIN transfer t ON o.payload->>'transferId' = t.id::text
    WHERE t.status = 'PENDING' AND o.aggregatetype = 'transfer.requested';
    ```
