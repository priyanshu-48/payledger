# ADR 0015: Ledger events, shared outbox module, notification service
- Status: accepted · Date: 2026-10-26
## Context
SPEC section 4: ledger-service publishes `ledger.events` and notification-service (logs or mock email) consumes them. Ledger-service needs the same outbox mechanism payment-service already has (ADR 0013).
## Decision
- Every journal entry posted by `TransferRecorder` (wallet transfers, payment captures and refunds) also inserts an outbox row `ledger.entry.posted` in the same transaction. Payload: entry id and type, external reference, both accounts and their owner ids, amount, currency. Topic `ledger.events`, key = entry id, 3 partitions.
- The poller and store move from payment-service into a new Maven module `outbox` used by both services. Each service keeps its own `outbox` table and migration. Writing rows uses `JdbcTemplate`, so it joins the caller's JPA transaction. The topic comes from `payledger.outbox.topic`.
- notification-service gets its own database `notifications`. Table `notifications(event_id, recipient, ...)` with primary key `(event_id, recipient)` is both the mock mailbox and the dedup record: insert with `ON CONFLICT DO NOTHING` and log the mock email only when a row was inserted. Each event notifies both the sender and the receiver.
- Failure policy copies the ledger consumer: unreadable payloads and exhausted retries go to `ledger.events.DLT`. No replay endpoint yet.
## Consequences
Known limit: a real email provider call cannot be made exactly once from a database transaction; it would need its own idempotency key or outbox. The mock log is not a delivery guarantee. Existing local Postgres volumes lack the `notifications` database and must be recreated (`docker compose down -v`).
## Alternatives rejected
Copying the poller into ledger-service (duplication, two places to fix); publishing from the consumer directly (violates the outbox rule); in-memory dedup in notification-service (lost on restart).
