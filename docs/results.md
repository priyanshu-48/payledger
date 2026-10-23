# Measured results

Numbers are from one developer machine (Windows 11, Docker Desktop, Postgres 16 in a container, JDK 21). Use them to compare strategies, not as absolute capacity claims.

## M2: concurrency harness (`ConcurrencyHarnessTest`, `-Dbench=true`)

64 threads call the transfer strategies directly (no HTTP) over 20 accounts (1 settlement, 19 wallets, all pre-funded), Hikari pool 64, 3 repeats at 3x the gate size. Expected balances are computed from the transfers that reported success, so a lost or doubled update would appear as drift.

- Scenario A, spread + hot account: 3,000 transfers, 30% involve one hot wallet.
- Scenario B, hot receiver: 6,000 transfers from 18 wallets, all crediting one wallet.

| Scenario | Strategy | Median transfers/s | Gave up (of N) per run | Retries per run | Balance drift | DB deadlocks |
|---|---|---|---|---|---|---|
| A | Pessimistic | 203 | 0 | n/a | 0 | 0 |
| A | Optimistic | 141 | 346-410 of 3,000 | 13k-15k | 0 | 0 |
| B (hot) | Pessimistic | 75 | 0 | n/a | 0 | 0 |
| B (hot) | Optimistic | 32 | 2,000-2,411 of 6,000 | 63k-70k | 0 | 0 |

"Gave up" = optimistic transfer abandoned after 20 conflicting attempts (HTTP 409 to the client; nothing was written, so no drift). Latency (p50 / p95 / p99, ms) for B: pessimistic about 270-600 / 2,400-3,300 / 3,400-5,000; optimistic about 1,000-1,200 / 2,800-3,500 / 3,100-4,100.

Reproduce: `./mvnw -pl common,ledger-service verify -Dtest=ConcurrencyHarnessTest -Dbench=true` (about 13 minutes); the per-run table is written to `ledger-service/target/concurrency-results.md`.

## SPEC section 11 rows from M1 and M2

| Metric | Value |
|---|---|
| Concurrent transfers, threads, balance drift | 27,000 per strategy (3 runs each of both scenarios), 64 threads, 0 drift |
| Pessimistic vs optimistic throughput (hot account) | 75 vs 32 transfers/s (and 32% of optimistic transfers abandoned) |
| jqwik generated scenarios, invariant violations | 10,000 / 0 |
