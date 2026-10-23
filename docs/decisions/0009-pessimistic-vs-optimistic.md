# ADR 0009: Pessimistic (default) vs optimistic locking for transfers
- Status: accepted · Date: 2026-10-24
## Context
SPEC 6.1 asks for an optimistic variant, benchmarked against the pessimistic transfer under contention (a hot account). CLAUDE.md requires a decision record before any new locking strategy.
## Decision
Both strategies exist behind `ledger.locking-strategy` (env `LEDGER_LOCKING_STRATEGY`). **Pessimistic stays the default.**
- Pessimistic: `SELECT ... FOR UPDATE` on both balance rows in sorted account-id order (ADR 0007).
- Optimistic: read balances without locks, update with `@Version` check, retry in a fresh transaction on conflict (max 20 attempts, random backoff up to 10 ms x attempt). Exhaustion returns HTTP 409 and writes nothing.
- `hibernate.order_updates=true` so optimistic commits write rows in primary-key order (avoids DB-level deadlocks between opposite transfers); Postgres deadlock errors are also retried.
- The insufficient-funds check in the optimistic path uses the snapshot it read; this equals ordering the transfer just before a concurrent credit, a valid serial order.
## Results (docs/results.md; 64 threads, 20 accounts, median of 3 runs)
| Scenario | Pessimistic | Optimistic |
|---|---|---|
| Spread + one hot account (3,000 transfers) | 203 transfers/s, 0 abandoned | 141 transfers/s, 346-410 abandoned |
| Hot receiver (6,000 transfers) | 75 transfers/s, 0 abandoned | 32 transfers/s, 2,000-2,411 abandoned |
Both: 0 balance drift, 0 database deadlocks, balances equal their postings.
## Why pessimistic wins here
With one hot row, 64 writers all read the same version, one wins, 63 conflict and redo all their work (13k to 70k retries per run). Pessimistic makes them queue on the row lock instead: slower per request but each transfer finishes once, and none are abandoned. Retries also burn database round trips that the queueing approach doesn't.
## When to switch to optimistic
When conflicts are rare (many accounts, no hot rows, mostly distinct payer/payee pairs): no lock waits, no held connections while queueing, and the retry cost is negligible. Re-measure with the harness before switching; in scenario A (30% hot) it was already behind. For a truly hot account the answer is neither: split the balance (sharded sub-balances) or project it asynchronously.
## Alternatives rejected
Serializable isolation with retries (heavier, same retry loop, less control); advisory locks (new mechanism, no gain over row locks).
## Caveats
One machine, Docker Desktop on Windows, direct service calls (no HTTP). Absolute numbers are not capacity claims; the ordering of the strategies is the finding.
