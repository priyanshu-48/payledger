# ADR 0008: Idempotency-Key becomes journal_entries.external_ref
- Status: accepted · Date: 2026-10-24
## Context
A retried POST /transfers must not move money twice.
## Decision
Header Idempotency-Key is stored as external_ref (UNIQUE). Replay with same body returns the original result; different body returns 422. A concurrent duplicate loses on the unique constraint, rolls back, and is answered by re-reading the winner in a new transaction.
## Alternatives rejected
Separate idempotency table (extra writes, no stronger guarantee for the ledger).
