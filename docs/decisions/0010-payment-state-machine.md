# ADR 0010: Payment state machine and the unknown state
- Status: accepted · Date: 2026-10-24
## Context
SPEC 5 lists CREATED, AUTHORIZED, CAPTURED, SETTLED, FAILED, REFUNDED. SPEC 6.6 says a bank timeout must leave the payment as AUTHORIZATION_UNKNOWN and a job must resolve it. That status is not in the SPEC 5 enum.
## Decision
Add AUTHORIZATION_UNKNOWN. Allowed transitions live in one map in `PaymentStatus`; anything else throws `IllegalTransitionException` (HTTP 409):
- CREATED -> AUTHORIZED | FAILED | AUTHORIZATION_UNKNOWN
- AUTHORIZATION_UNKNOWN -> AUTHORIZED | FAILED
- AUTHORIZED -> CAPTURED | FAILED
- CAPTURED -> SETTLED | REFUNDED
- SETTLED -> REFUNDED
- FAILED and REFUNDED are terminal.
Every change is `UPDATE payments SET status=:to, version=version+1 WHERE id=:id AND status=:from`; zero rows means another actor moved it first. Each change also inserts a `payment_events` row in the same transaction. Bank faults apply to authorization only; capture and refund calls to the bank are reliable. SETTLED has no endpoint until reconciliation (M6). Refunds are full refunds only.
## Alternatives rejected
Optimistic `@Version` on the entity (the conditional UPDATE is the SPEC and avoids a read-modify-write); keeping the timeout as CREATED (indistinguishable from a request still in flight).
