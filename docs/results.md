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


## M8: observability

| Check | Result |
|---|---|
| Full `./mvnw verify` | 145 tests, 0 failures (common 5, security 8, auth-service 9, ledger 80, payment 38, notification 5) |
| Payments-by-status gauge | each status counted, unused statuses 0 (test) |
| Outbox lag gauge | rows backdated 2 min: age >= 120 s and backlog 2; after publishing both 0 (test). The test found a bug: an empty outbox gave NaN instead of 0, fixed |
| Transfer timer | created, replayed and refused transfers each land in their own `outcome`; histogram buckets published (test) |
| Recon counters | a run adds exactly its mismatches; re-uploading the same file adds nothing (test) |
| Consumer lag | consumer stopped, 7 messages published: lag 7; restarted: 0 (test, real Kafka) |
| Trace id in consumer logs | log line of the listener carries the trace id of the publishing span (test) |
| Real stack (`ops/observability/dashboard-demo.sh`) | PASS: all 14 dashboard queries return data; outbox lag peaked near 40 s with Kafka stopped (235 unpublished rows); recon found 3/2/4/3 planted mismatches; 429s from the login burst; screenshot `docs/img/dashboard.png` |

## M9: admin console

| Check | Result |
|---|---|
| Full `./mvnw verify` | 151 tests, 0 failures (common 5, security 8, auth-service 9, ledger 84, payment 40, notification 5) |
| `npm run build` (strict TypeScript + Vite) | clean; bundle 179 kB (58 kB gzip) |
| New endpoints | account list (admin only, filters, size cap, paging without overlap), payment list (admin all, merchant own, status filter, user 403), recon run list (admin only, newest first) |
| Playwright smoke test against the real stack (`ops/ui/smoke.sh`) | 3 of 3 passed in about 7 s: wrong password refused; admin freezes/unfreezes an account, reads a 3-step payment history, captures a payment from the UI, uploads a reconciliation file and sees the mismatch, runs dead-letter replay; merchant sees only its own 2 payments and no admin menus; a reload signs out |

## Jenkins pipeline

| Check | Result |
|---|---|
| Full pipeline in the Jenkins container | SUCCESS in about 19 min: Maven `verify` 151 tests (common 5, security 8, auth 9, ledger 84, payment 40, notification 5), admin-ui build, Playwright 3 of 3 |
| Stage times (average) | Backend 9 min 18 s (14 min on a cold build), Admin UI 31 s, Stack smoke 2 min 54 s |
| Failure path | A deliberately failing test in `common` gave a red build in 90 s, 1 failed / 5 passed in the JUnit report, stack stage not started |
| What the first runs found | Three failed builds before the first green: workspace path and local checkout settings (configuration), then a race in my smoke test (balance read while a capture was still being posted), fixed by asserting on an untouched wallet |

## M10: load test (Gatling, `ops/load/run.sh`)

**How it is measured.** Gatling drives the real stack (all four services, Postgres, Kafka, Prometheus, Jaeger) in its own compose project. One run per request rate, 30 seconds each, at a constant arrival rate (open workload), after a short warm-up that is not counted. Every step starts from a rested system (all events published and posted), and a ladder stops at its first step that is over the limit. A rate is **sustained** when it has under 1% failed requests and a p95 under 500 ms. Rate limits are lifted for the run, the stand-in bank answers in 2-20 ms without faults, and the 200 test wallets are picked at random (no hot account). All of it runs on one laptop whose Docker VM shares CPU between the load generator, the database, Kafka and every service, so the numbers describe that machine, not a cloud VM and not a tuned deployment.

**Final run** (warm-up, drain between steps):

| Workload | Rate | Requests | Failed | p50 | p95 | p99 | Verdict |
|---|---|---|---|---|---|---|---|
| `POST /transfers` | 200/s | 6,000 | 0% | 10 ms | 17 ms | 28 ms | sustained |
| | 400/s | 12,000 | 0% | 10 ms | 18 ms | 94 ms | sustained |
| | 600/s | 18,000 | 0% | 12 ms | 44 ms | 234 ms | **sustained** |
| | 800/s | 24,000 | 0% | 17 ms | 642 ms | 786 ms | over the limit |
| `POST /payments` + capture | 120/s (240 requests/s) | 7,200 | 0% | 22 ms | 73 ms | 428 ms | **sustained** |
| | 240/s (480 requests/s) | 14,400 | 19% | 28 ms | 16,256 ms | 19,890 ms | over the limit |

**Correctness under load** (11,250 payments created in that run, 9,850 captured, 33,679 events): everything published and processed, 9,850 capture entries for 9,850 distinct captures, **0 lost, 0 duplicated**, sum of all balances 0. Earlier runs of the same script created up to 39,152 payments with the same result.

**What the earlier runs taught** (kept here because they changed the method):

| Run | What happened | Change made |
|---|---|---|
| 1 | Transfers failed from 300/s with "Address already in use" on the load generator | Each virtual user opened a new connection and exhausted this machine's local ports; I was measuring the test client. Users now share one connection pool, as a real client does. |
| 2-3 | Transfers: 600/s sustained, 800/s not. Payments: 240/s sustained once, then 360/s collapsed (about 99% failed, 60 s timeouts) | Steps ran back to back, so a step started while the previous one's events were still being posted. The script now waits for everything to drain before each step. |
| 3 | The first payment step was slow (p95 870 ms) after the transfer ladder | JVM and connection warm-up; a warm-up phase was added. |
| 5 (final) | Payments: 120/s sustained, 240/s failed (19%) although 240/s had passed cleanly (p95 61 ms) in two earlier runs | Not explained by one cause; the payment path is at its edge between 120 and 360 per second on this machine (the payment service used about 4 cores and Postgres about 3 while a 360/s step ran). I report the lower, repeatable figure. |

**Reading the numbers.** The transfer ceiling (600-800/s) is a single machine saturating (CPU shared by Postgres, the service and the load generator), not a design limit: going further would mean more machines and partitioning accounts across ledger instances, which is not built. The payment path does more work per request (a bank call, two state transitions with event rows and two outbox rows) and, below it, every capture credits the same merchant account, whose balance row is serialised by design (ADR 0007, and the 75 transfers/s hot-account figure from M2).

**Coverage** (JaCoCo, all main code of all modules, `ops/ci/coverage.sh` after `./mvnw verify`): 92.8% of lines (1,389 of 1,496) and 82.2% of branches (296 of 360).
