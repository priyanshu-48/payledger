# PayLedger

Payments and double-entry ledger platform (Java 21, Spring Boot, PostgreSQL, Kafka).

## Run
```bash
cp .env.example .env   # set passwords
docker compose up -d --build
docker compose ps      # all services healthy
```
Health: `:8081` payment, `:8082` ledger, `:8083` notification (`/actuator/health`). Prometheus `:9090`, Grafana `:3000`.

## Test
```bash
./mvnw verify          # needs Docker running (Testcontainers)
```

## Locking strategy
`LEDGER_LOCKING_STRATEGY=PESSIMISTIC` (default) or `OPTIMISTIC` selects how `POST /transfers` serialises concurrent updates to the same account. Trade-offs and measurements: `docs/decisions/0009-pessimistic-vs-optimistic.md`, `docs/results.md`.

## Security (auth-service, :8084)
Every API needs a JWT from auth-service (ADR 0018, 0019). The first user, `admin` (role ADMIN), is created from `AUTH_ADMIN_PASSWORD` in `.env`; only an ADMIN creates other users (roles USER, MERCHANT, ADMIN).
```bash
ADMIN=$(curl -s -X POST localhost:8084/auth/token -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$AUTH_ADMIN_PASSWORD\"}" | sed 's/.*"accessToken":"\([^"]*\)".*/\1/')
curl -X POST localhost:8084/auth/users -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"username":"m1","password":"a-long-enough-password","role":"MERCHANT"}'
```
The token's subject is matched with the owner of accounts (ledger `owner_id`) and payments (`merchantId`): a USER sees only their own wallet, a MERCHANT only its own payments, an ADMIN everything and the operator endpoints (`/recon/*`, `/admin/*`, account freeze). Money-moving endpoints are rate limited per client (429 with `Retry-After`). Tokens last 15 minutes.

## Payments (payment-service, :8081)
Log in as a MERCHANT user (above), put its name in `merchantId`, and send its token:
```bash
curl -X POST localhost:8081/payments -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $MERCHANT_TOKEN" -H 'Idempotency-Key: pay-1' \
  -d '{"merchantId":"m1","payerAccountId":"00000000-0000-0000-0000-000000000042","amount":500.00,"currency":"INR"}'
```
Then `POST /payments/{id}/capture`, `POST /payments/{id}/refund`, `GET /payments/{id}/events`. The stand-in bank lives at `/mock-bank` and fails on purpose (declines, timeouts, lost responses; see `MOCK_BANK_*` in `docker-compose.yml`). A 202 means the bank's answer is unknown; the status-check job resolves it.

## Events: outbox, Kafka, ledger consumer
Every payment state change writes an outbox row in the same database transaction; a poller publishes the rows to the `payments.events` topic (at least once). The ledger consumes them idempotently (`processed_events`), posts `PAYMENT_CAPTURE` / `REFUND` journal entries, and sends events it cannot apply to `payments.events.DLT`. After fixing the cause (for example creating the missing merchant account), `POST localhost:8082/admin/dlt/replay` re-queues them. Design: `docs/decisions/0013-transactional-outbox.md`, `0014-idempotent-ledger-consumer.md`.

Prove it yourself (kills the ledger mid-stream and checks 0 lost, 0 duplicated; uses its own compose project):
```bash
ops/chaos/kill-ledger.sh 300
```
Ledger payments need a MERCHANT account whose `ownerId` equals the payment's `merchantId`.

## Notifications and traces
Every journal entry the ledger posts also queues a `ledger.entry.posted` event (ledger outbox); notification-service consumes it and stores two mock emails (`[MOCK EMAIL]` in its log, rows in the `notifications` database). All three services export traces to Jaeger (http://localhost:16686 when the stack is up). The trace id travels through the outbox rows and Kafka headers, so one payment capture is a single trace across payment-service, ledger-service and notification-service.

Prove it (own compose project, fresh volumes):
```bash
ops/e2e/trace-check.sh
```
After upgrading from M4: Postgres only creates its databases on a fresh volume, so run `docker compose down -v` once to get the `notifications` database, and add `NOTIFICATIONS_DB_PASSWORD` to your `.env` (see `.env.example`).

## Bank reconciliation
`POST /recon/runs` (multipart `file` plus `periodStart` and `periodEnd`) compares the bank's settlement CSV (`external_ref,amount_minor,currency,settled_at`) with the ledger's `PAYMENT_CAPTURE` entries for that period and stores one outcome per reference: `MATCHED`, `MISSING_IN_LEDGER`, `MISSING_IN_BANK`, `AMOUNT_MISMATCH` or `DUPLICATE_IN_BANK`. Read it back with `GET /recon/runs/{id}` and `GET /recon/runs/{id}/items?outcome=...` (mismatches by default). Submitting the same file for the same period again returns the same run; a run that failed part-way is resumed, not repeated. Design: `docs/decisions/0017-bank-reconciliation.md`.

Try it end to end (plants errors in a generated bank file and checks every one is found; own compose project):
```bash
ops/recon/run-demo.sh 40
```

## Observability: metrics and dashboard
Every service exposes Prometheus metrics at `/actuator/prometheus`; Prometheus (:9090) scrapes them and Grafana (:3000, read-only without login) loads `ops/grafana/dashboards/payledger.json`.

![PayLedger dashboard](docs/img/dashboard.png)

Taken from `ops/observability/dashboard-demo.sh`: payments with bank faults, Kafka stopped for a while (outbox lag), ledger-service killed during traffic (consumer lag), a reconciliation run with planted errors (3 amount mismatches, 2 duplicates, 4 missing in bank, 3 missing in ledger), and a burst of bad logins (429). The same script runs every dashboard query against Prometheus and fails if one is empty.

| Metric | Meaning |
|---|---|
| `payments_by_status{status}` | payments in each status (from the database) |
| `ledger_transfer_duration_seconds` | transfer latency histogram, tagged `outcome` (created, replayed, refusal type) |
| `outbox_oldest_unpublished_age_seconds`, `outbox_unpublished` | outbox lag and backlog (payment and ledger) |
| `kafka_consumer_group_lag{group,topic}` | messages the consumer group is behind, asked of the broker |
| `recon_mismatches_total{outcome}`, `recon_runs_total{status}` | reconciliation results |

Consumer log lines carry the trace id of the request that caused the event, so one id finds the HTTP call, the outbox publish, the ledger posting and the notification. Decisions: [ADR 0020](docs/decisions/0020-observability.md).

## Admin console (admin-ui, :8085)
React + TypeScript + Vite, served by nginx, which also forwards `/api/<service>/` to the services so the browser stays on one origin ([ADR 0021](docs/decisions/0021-admin-ui.md)). Sign in with a user from auth-service (the bootstrap `admin` sees everything; a MERCHANT sees only its own payments).

![Payment detail with status history](docs/img/admin-ui.png)

- **Accounts:** balances, owner and type filters, freeze and unfreeze.
- **Payments:** status filter, detail with the full status history, capture and refund where the state allows.
- **Reconciliation:** run list with counts, mismatches of a run, upload of a bank settlement file.
- **Operations:** dead-letter replay.

Development: `cd admin-ui && npm ci && npm run dev` (proxies to services on localhost). Smoke test: `ops/ui/smoke.sh` starts the stack in its own compose project and runs the Playwright test against it using the installed Microsoft Edge.

## CI/CD: Jenkins
The pipeline is in the [`Jenkinsfile`](Jenkinsfile) and Jenkins itself is code in [`jenkins/`](jenkins) ([ADR 0022](docs/decisions/0022-jenkins.md)); GitHub Actions (`ci.yml`) also builds every push.

![Jenkins stage view](docs/img/jenkins.png)

```bash
docker compose -f jenkins/docker-compose.yml --env-file .env up -d --build   # http://localhost:8090
```

Stages: throwaway `.env` with random secrets, then Maven `verify` (all backend tests with Testcontainers) in parallel with the admin-ui type-check and build, then the real-stack Playwright smoke test (`ops/ui/smoke.sh`). Jenkins reaches Docker through the host's socket, polls the repository every five minutes, and is configured entirely by `jenkins/jenkins.yaml` (sign in as `admin` with `JENKINS_ADMIN_PASSWORD` from `.env`; anyone on localhost can view).
