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

## Payments (payment-service, :8081)
```bash
curl -X POST localhost:8081/payments -H 'Content-Type: application/json' \
  -H 'X-Client-Id: demo' -H 'Idempotency-Key: pay-1' \
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
