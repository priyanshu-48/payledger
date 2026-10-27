# PayLedger: facts for resume bullets

Give this file to Claude with: "Write resume bullet points for this project using only the facts in this file. Do not add numbers or claims that are not here. Follow the 'Do not claim' list." Every number was cross-checked against the test reports, the load-test logs and the code (October 2026); the source of each is named.

## One-line description
PayLedger is a payments platform with a double-entry ledger: payments are authorized and captured against a stand-in bank, and each capture becomes a balanced, append-only journal entry in a separate ledger service. It is a solo project built to show what stays correct when things fail (duplicate requests, a bank that times out, a consumer killed mid-stream, concurrent transfers on one account).

## Tech stack
Java 21, Spring Boot 3.5, PostgreSQL 16, Apache Kafka, Spring Batch, Spring Security (JWT, role-based access), Flyway SQL migrations, React 18 + TypeScript (Vite), nginx, Docker Compose, Testcontainers, JUnit 5, jqwik (property-based tests), Gatling (load tests), JaCoCo, Playwright, Prometheus, Grafana, Jaeger (OpenTelemetry tracing), Jenkins (configuration as code) and GitHub Actions.

## Architecture in short
- Five Maven modules plus shared libraries: auth-service (issues JWTs), payment-service (payment state machine, stand-in bank), ledger-service (accounts, transfers, journal, reconciliation), notification-service (mock emails), admin-ui (React console).
- Services never share a database. They communicate through Kafka events written with the transactional outbox pattern (state change and outbox row in one DB transaction; a poller publishes afterwards, at least once).
- The ledger consumer is idempotent (a `processed_events` table), sends unprocessable events to a dead-letter topic, and an admin endpoint replays them.
- 23 architecture decision records (ADRs) in `docs/decisions/`.

## Correctness design (what makes it interesting)
- Money is `long` minor units, never floating point; currency must match on every transfer.
- Journal entries and postings are append-only, enforced by database triggers; corrections are reversal entries. Every entry must balance (debits equal credits), checked in code and by a database constraint. Wallets cannot go negative (DB constraint).
- Concurrent transfers lock balance rows in sorted account-id order (no deadlocks). Two strategies were built and compared: pessimistic locking and optimistic locking with retries.
- Idempotency keys on transfers and payments (same key and same body returns the same result; same key with a different body is rejected).
- Unknown bank outcomes (timeouts, lost responses) become `AUTHORIZATION_UNKNOWN` and a scheduled status job resolves them by asking the bank, never by guessing.
- Bank reconciliation with Spring Batch: compares a bank settlement CSV with ledger captures and classifies each reference as matched, missing in ledger, missing in bank, amount mismatch or duplicate in bank; restartable and idempotent per file.
- Security: JWT from a separate auth service, roles USER / MERCHANT / ADMIN with ownership checks (a user sees only their own wallet, a merchant only its own payments), per-client rate limits (429 with Retry-After), login brute-force limit, BCrypt, audit logs of admin actions, tests that fail if passwords, tokens or card numbers reach logs.
- Observability: Prometheus metrics (payments by status, transfer latency histogram, outbox lag, Kafka consumer lag, reconciliation counters), a provisioned Grafana dashboard, and one trace id carried across HTTP, the outbox, Kafka and three services.

## Verified numbers (with source)
| Fact | Value | Source |
|---|---|---|
| Automated tests | 152, 0 failures (common 5, security 8, auth 10, ledger 84, payment 40, notification 5) | `./mvnw verify` reports |
| Code coverage | 92.8% of lines (1,389 of 1,496), 82.2% of branches (296 of 360), all modules, JaCoCo | `ops/ci/coverage.sh` |
| Concurrent transfer test | 27,000 transfers per locking strategy, 64 threads, 0 balance drift, 0 database deadlocks | `docs/results.md` M2 |
| Locking comparison on a hot account (6,000 transfers into one wallet, 64 threads) | pessimistic 75 transfers/s vs optimistic 32 transfers/s; optimistic abandoned 2,000-2,411 of 6,000 (33-40%) after 20 conflicting attempts | `docs/results.md` M2 |
| Property-based test (jqwik) | 10,000 generated scenarios, 0 invariant violations (1,000 per normal build) | `docs/journal.md` M1 |
| Payment idempotency | 50 identical parallel requests produce 1 payment and 1 bank call | `PaymentIdempotencyParallelTest` |
| Bank fault injection | 1,000 payments against a bank with 5% declines, 3% timeouts, 1% lost responses: about 35 ended unknown, all resolved by the status job, 0 stuck | `BankChaosTest`, journal M3 |
| Consumer crash test (real containers) | ledger-service killed with 471 of 900 events still unprocessed; after restart 0 lost, 0 duplicated postings (300 payments) | `ops/chaos/kill-ledger.sh`, results M4 |
| Reconciliation | 200-capture test: 19 planted errors, 19 found, 0 false positives; real-stack demo: 11 planted, 11 found, 0 false positives; same file uploaded twice returns the same run | results M6 |
| Load, transfers | 600 requests/s sustained (0% failed, p50 12 ms, p95 44 ms, p99 234 ms); 800/s degrades (p95 642 ms) | `ops/load/run.sh`, results M10 |
| Load, payments (create + capture) | 120 payments/s (240 requests/s) sustained, p95 73 ms; 240 payments/s failed 19% in the final run | results M10 |
| Correctness under load | 11,250 payments, 33,679 events: 0 lost, 0 duplicated, sum of all balances 0 | results M10 |
| Jenkins pipeline | full run about 19 min: Maven verify, admin-ui build, Playwright end-to-end test against the real stack | results, ADR 0022 |
| Admin console | React + TypeScript, 3 Playwright end-to-end tests against the real stack, bundle 58 kB gzipped | results M9 |

Load numbers were measured on one laptop where the load generator, database, Kafka and all services share one Docker VM, with a stand-in bank.

## Process facts (honest and usable)
- Built milestone by milestone from a written specification (M0 to M10), each with tests, an ADR for design choices and a journal entry; a first load test run measured the test client's own port exhaustion, which was found and fixed (shared connections, warm-up, draining between steps).
- Tests found real defects in the author's own code (a bug where an empty outbox reported NaN lag; two places where a password and a token were printed in debug logs; a login rate limit that would have been shared by all users behind a proxy).
- AI coding assistance (Claude Code) was used in development; the owner wrote the specification and reviews. Mention this only if asked or if the resume format needs it.

## Do not claim
- No live cloud deployment exists. A deployment kit (Docker Compose, Caddy automatic HTTPS, provisioning scripts) is written and was tested locally only.
- No real users or production traffic; the bank is a stand-in.
- The load numbers are one laptop, not capacity claims. Never say "handles 600 TPS in production".
- No multi-instance scaling, no Kubernetes, no managed database or Kafka.
- Demo video: not recorded yet.
- Do not call the project "PCI compliant" or "bank-grade"; it handles no real card data.

## Example bullet shapes (for style only; check every number against the table)
- Designed a double-entry ledger in Java and PostgreSQL where append-only journals and balanced entries are enforced by database triggers and constraints, and verified it with 27,000 concurrent transfers (64 threads) and 10,000 property-based scenarios with 0 balance drift.
- Implemented the transactional outbox and an idempotent Kafka consumer; killing the consumer with 471 events in flight and restarting it produced 0 lost and 0 duplicated postings.
- Load-tested with Gatling: the transfer API sustained 600 requests/s (p95 44 ms) on a laptop, with 0 lost or duplicated events and balances summing to zero after 11,250 payments.
- Built 152 automated tests (92.8% line coverage) run in CI on both Jenkins and GitHub Actions, including Testcontainers integration tests and a Playwright end-to-end test against the full stack.
