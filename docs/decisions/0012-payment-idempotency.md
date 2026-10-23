# ADR 0012: Idempotent payment creation
- Status: accepted · Date: 2026-10-24
## Context
SPEC 6.3: key unique per client, request hashed with SHA-256, same key + same hash returns the stored response, same key + different hash 422, same key while the first request is processing 409.
## Decision
- `X-Client-Id` header stands in for the authenticated client until M7; `Idempotency-Key` header is required.
- Hash = SHA-256 hex of the fixed-order string `merchantId|payerAccountId|amountMinor|currency` (fixed schema, so no JSON canonicalisation is needed).
- Flow: (1) short transaction inserts the payment as CREATED plus an event row; `UNIQUE(client_id, idempotency_key)` decides races. (2) The bank call happens **outside** any DB transaction. (3) A second short transaction applies the conditional transition and event.
- Duplicate request: hash differs -> 422; status CREATED (first request still in flight) -> 409; otherwise 200 with the current state (202 while AUTHORIZATION_UNKNOWN). A new payment returns 201 (202 if it ended unknown). "Stored response" is the current payment representation.
- Crash recovery: a crash after step 1 leaves a CREATED row. The status-check job treats stale CREATED like AUTHORIZATION_UNKNOWN, after a grace period (default 15 s, longer than the bank timeout) so it never races an in-flight request.
## Alternatives rejected
Holding the DB transaction open across the bank call (pins a connection for seconds); storing the response body separately (more state, same observable behaviour).
