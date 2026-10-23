# ADR 0014: Idempotent ledger consumer, retries, dead letters, replay
- Status: accepted · Date: 2026-10-24
## Context
Kafka delivers at least once, and the outbox poller can publish an event twice (ADR 0013). The ledger must post each payment event exactly once in effect (SPEC 6.5).
## Decision
- One database transaction per event: `INSERT INTO processed_events (event_id) ... ON CONFLICT DO NOTHING`; zero rows inserted means a duplicate and the event is skipped; otherwise post the journal entry through `LedgerService.transfer` (it joins the transaction) and commit. The Kafka offset is committed after that (ack mode RECORD). A crash between the two causes a redelivery, which `processed_events` ignores. The journal's unique `external_ref` is a second guard.
- Posting rules: `payment.captured` -> PAYMENT_CAPTURE entry (debit payer wallet, credit merchant account, `external_ref = payment-capture:<paymentId>`); `payment.refunded` -> REFUND entry in the opposite direction (`payment-refund:<paymentId>`). Other event types are recorded as processed with no posting.
- The merchant account is found by `owner_id = merchantId`, type MERCHANT, and currency; a partial unique index (ledger V3) makes that lookup unambiguous.
- Failures: business failures (insufficient funds, unknown merchant or account, frozen account, currency mismatch, unreadable message) are not retried and go straight to `payments.events.DLT`. Anything else is retried three times with exponential backoff, then dead-lettered. A failed event leaves no `processed_events` row, because the insert rolls back with the transaction.
- Replay: `POST /admin/dlt/replay` reads the DLT with its own consumer group, republishes each message to its original topic, commits per record, and returns the count. ADMIN-only once security lands (M7).
- Consumer starts from `earliest` so events published before the first start are not skipped. Concurrency 3 matches the 3 partitions; events of one payment stay ordered because the key is the payment id.
## Known gap
A capture whose payer lacks funds is dead-lettered while the payment stays CAPTURED in the payment service. Resolving that needs a compensation flow (cancel or fail the payment), not designed yet.
## Alternatives rejected
Exactly-once Kafka transactions (do not cover the database write); dedup only through `external_ref` (does not skip non-posting events and uses an exception as control flow).
