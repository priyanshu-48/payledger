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

## M4: kill the ledger consumer with events in flight (`ops/chaos/kill-ledger.sh 300`)

Real containers (Postgres, Kafka, payment-service, ledger-service) in their own compose project. 300 payments are created and captured 12 at a time (3 events each = 900 events); `docker kill` stops ledger-service after 429 events were processed (471 still to go, 1 outbox row not yet published); payments keep arriving while the ledger is down; the ledger is restarted and left to drain.

| Check | Result |
|---|---|
| Payments captured (payment DB) | 300 |
| `PAYMENT_CAPTURE` journal entries / distinct external refs | 300 / 300 |
| Lost / duplicated postings | **0 / 0** |
| Postings on capture entries | 600 (expected 600) |
| Events in outbox / processed by the ledger | 900 / 900 |
| Merchant balance / sum of all balances | 300,000 minor units (expected) / 0 |

JVM-level companion (`ConsumerRecoveryTest`): 300 events, consumer stopped after 122 and restarted, then group offsets rewound to before the first event and all 300 re-read: still 300 entries, 600 postings, 300 `processed_events`.

SPEC section 11 row: events in flight when the consumer was killed -> lost / duplicated = 471 -> 0 / 0.

## M5: one trace across three services (`ops/e2e/trace-check.sh`)

One funded wallet, one merchant, one payment created and captured through the real stack (Postgres, Kafka, Jaeger, three services in containers). Jaeger holds a single trace with 7 spans across payment-service, ledger-service and notification-service; the `[MOCK EMAIL]` log lines of the notification service carry the same trace id as the request that started it. Four mock emails were stored (2 for the funding transfer, 2 for the capture).

## M6: bank reconciliation with planted errors

**Automated (`ReconciliationTest`):** 200 captures in the period, 20 older captures (17 listed in the bank file, 3 not) and one plain transfer in the ledger. Planted in the bank file: 5 dropped rows, 5 amount changes plus 2 currency changes, 3 rows listed twice, 4 invented references.

| Outcome | Planted | Found |
|---|---|---|
| MISSING_IN_BANK | 5 | 5 |
| AMOUNT_MISMATCH | 7 | 7 |
| DUPLICATE_IN_BANK | 3 | 3 |
| MISSING_IN_LEDGER | 4 | 4 |
| MATCHED | 202 | 202 |
| False positives (the 3 unlisted older captures, the plain transfer) | 0 | 0 |

**Restart (`ReconRestartTest`):** writer fails on the 4th chunk of 10 (30 rows committed); the same upload resumes the failed run and ends with exactly 100 staged rows, 100 items, 1 run, 1 job instance, 2 executions.

**Real stack (`ops/recon/run-demo.sh 40`):** 40 payments created and captured through payment-service, Kafka and the ledger consumer; a bank file generated from the ledger with 3 dropped, 3 altered, 2 duplicated rows and 3 invented references. Result: 11 planted, 11 found correctly, 0 missed, 0 false positives; the second upload of the same file returned HTTP 200 with the same run id and no new items (43 -> 43).

SPEC section 11 row: Recon rows / planted mismatches found -> 11 planted in a 40-capture demo (and 19 in the 200-capture test): all found.

## M7: security

| Check | Result |
|---|---|
| Full `./mvnw verify` | 138 tests, 0 failures (common 5, security 8, auth-service 9, ledger 75, payment 36, notification 5) |
| User A reads user B's wallet | 403 (HTTP test and a service-layer test with no HTTP); own wallet 200 |
| User A debits user B's wallet / credits it | 403 / 201 (peer to peer allowed) |
| Merchant B reads, captures or refunds merchant A's payment | 403 for all four; ADMIN reads any, gets 404 for a missing id; non-admin gets 403 for a missing id |
| No token, expired, wrong issuer, foreign signing key, tampered | 401 every time |
| USER or MERCHANT on `/recon`, `/admin/dlt/replay`, account freeze | 403; ADMIN allowed, freeze/unfreeze/replay/recon/admin account creation/user creation audited |
| Rate limit (limit 3 in the test) | 4th request 429 with `Retry-After` and `X-RateLimit-Remaining: 0`; other clients unaffected; reads not limited |
| Login brute force (limit 3 in the test) | 429 even with the right password |
| Passwords | BCrypt hashes only; same 401 for unknown user and wrong password |
| Mock bank | 401 without the key, 401 with a wrong key, reaches the bank with the right key |
| Secrets in logs | Log-capture tests in auth, ledger and payment: no password, token, `Authorization` value, bank key or card-like number (3 test PANs) in logs or error responses. The test found two real leaks (password and token printed by request/response `toString` at DEBUG), now masked. |
| Real stack, secured (`ops/e2e/trace-check.sh`) | PASS: one trace of 12 spans across payment, ledger and notification, 4 mock emails |
| Real stack, secured (`ops/chaos/kill-ledger.sh 300`) | PASS: killed after 403 of 900 events; 300 captured, 300 ledger entries, 600 postings; 0 lost, 0 duplicated |
| Real stack, secured (`ops/recon/run-demo.sh 40`) | PASS: 11 planted errors, 11 found, 0 false positives; re-upload returned the same run, 43 items before and after |

Load scripts raise the per-client limits for their own run only (`TRANSFER_RATE_PER_MINUTE`, `PAYMENT_RATE_PER_MINUTE`); defaults are 30 a minute.

