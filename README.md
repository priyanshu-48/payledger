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
