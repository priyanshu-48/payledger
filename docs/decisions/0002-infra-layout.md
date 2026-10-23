# ADR 0002: Local infra layout
- Status: accepted · Date: 2026-10-24
## Decision
One Postgres 16 server with two databases (`ledger`, `payments`) and one login per service, created by `ops/postgres/init.sh` (passwords from env). Single-node KRaft Kafka (apache/kafka). All services built from one multi-stage Dockerfile (`MODULE` build arg). Config only via env vars; real values live in gitignored `.env`.
## Alternatives rejected
Two Postgres containers (more RAM, no extra isolation lesson); Redpanda (revisit only if Kafka RAM bites); per-service Dockerfiles (duplication).
## Consequences
Services never share tables (separate DBs and roles). One Dockerfile means one place to fix image issues.
