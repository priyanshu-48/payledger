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
