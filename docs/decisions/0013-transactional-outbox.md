# ADR 0013: Transactional outbox with a lease-based poller
- Status: accepted · Date: 2026-10-24
## Context
Payment state changes must reach the ledger through Kafka. Writing the database and publishing to Kafka are two systems: a crash between them loses or invents events. SPEC 6.4 asks for an outbox and a decision record on why not two-phase commit and why a poller rather than Debezium. CLAUDE.md: state change and outbox row in the same transaction; never publish to Kafka inside a DB transaction.
## Decision
- Every payment transition inserts an `outbox` row in the same transaction as the status change and history row. The row id is the event id.
- A poller publishes pending rows. It must not hold a transaction while talking to Kafka, so it works in three steps: (1) one statement claims up to 100 rows by setting `claimed_until = now() + lease` and `attempts = attempts + 1`, selecting them with `FOR UPDATE SKIP LOCKED`; it commits immediately; (2) it publishes with `acks=all` and an idempotent producer and waits for the broker; (3) a second statement sets `published_at`.
- A crash after step 2 and before step 3 means the row is claimed again after the lease expires and the event is published twice: delivery is at-least-once. Consumers deduplicate on the event id (ADR 0014).
- Ordering: a row is claimable only if no earlier unpublished row of the same `aggregate_id` exists (ordered by the `seq` column), and Kafka messages are keyed by payment id. So a payment's events reach a partition in order even with several pollers.
- Added to the SPEC 5 outbox table: `seq BIGSERIAL` (stable global order) and `claimed_until` (the lease).
- Guarantee: an event is published if and only if its database change committed, at least once.
## Why not two-phase commit
Postgres and Kafka do not share a transaction coordinator; XA across them is not available for Kafka and would couple availability of the payment API to the broker. The outbox keeps the payment write local and lets Kafka be down without failing payments.
## Why a poller and not Debezium
A poller is one class and one table, runs anywhere the service runs, and is easy to test. Debezium (log-based CDC) removes polling latency and load but adds Kafka Connect and connector operations. It is the planned next step if throughput or latency requires it.
## Consequences
Publishing latency is the poll interval (200 ms). Throughput is bounded by one event per payment per sweep. If Kafka is down, rows accumulate and drain when it returns.
