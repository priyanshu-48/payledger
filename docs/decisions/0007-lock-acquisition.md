# ADR 0007: Pessimistic locks taken one row at a time in sorted id order
- Status: accepted · Date: 2026-10-24
## Context
Transfers must not deadlock (SPEC 6.1). Postgres does not guarantee that ORDER BY ... FOR UPDATE locks rows in the ORDER BY sequence.
## Decision
findForUpdate(accountId) uses PESSIMISTIC_WRITE; transfer() calls it for the smaller id first, then the larger.
## Alternatives rejected
One IN (...) FOR UPDATE query (lock order not guaranteed); optimistic locking (benchmarked in M2 as a variant, per spec).
