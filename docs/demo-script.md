# Demo video script (about 4 minutes)

Record the screen with your voice; keep it plain and calm. Everything below uses the live site (or the local stack: `docker compose up -d --build`, then http://localhost:8085). Have two browser windows open: the console signed in as `admin` (private) and a terminal.

| Time | Show | Say |
|---|---|---|
| 0:00 | README top: title and the metrics table | "PayLedger is a payments platform with a double-entry ledger: four Spring Boot services, Kafka, Postgres. I built it to show correctness under failure, not just happy paths." |
| 0:20 | `docs/architecture.md`: components diagram, then the payment sequence | "A payment is created, authorized by a stand-in bank, captured. The state change and an outbox row are one transaction; a poller publishes to Kafka; the ledger consumes and posts a balanced entry." |
| 0:50 | Console, **Payments**, open a captured payment | "Every status change is recorded; this is the history. Timeouts end in an unknown state that a job resolves by asking the bank, never by guessing." |
| 1:20 | Console, **Accounts** | "Balances come from the ledger. An admin can freeze an account; that takes the same row lock as a transfer, so it cannot race one." |
| 1:40 | Terminal: `ops/chaos/kill-ledger.sh 300` output (or the recorded result in `docs/results.md`) | "The chaos test kills the ledger service with events in flight, restarts it: 300 payments, 0 lost, 0 duplicated postings, because the consumer records each event id in the same transaction as the posting." |
| 2:20 | Console, **Reconciliation**: upload a file, show the mismatches | "Reconciliation compares the bank's settlement file with the ledger using one set-based query and finds every planted mismatch: missing in bank, missing in ledger, amount, duplicate." |
| 2:50 | Grafana dashboard (`/grafana/`) | "Payments by status, transfer latency, outbox lag, consumer lag, reconciliation mismatches. The lag and outbox-age spikes are from the failure drill: Kafka stopped, then the ledger killed, then everything draining." |
| 3:20 | Jenkins stage view (`docs/img/jenkins.png` or live) | "Jenkins, defined as code, runs the 151 tests, builds the UI and runs a browser test against the whole stack." |
| 3:40 | README: load-test table | "On my laptop it sustains the rates in this table at p95 under 500 ms with nothing lost; the numbers are in the repo with how they were measured." |
| 3:55 | Demo login (merchant) on the live URL | "Try it yourself: the demo merchant login is in the README; it can only see its own payments." |

Tips: record at 1080p, zoom the browser to 125%, close notifications, and do one dry run. Do **not** show `.env`, the admin password or the SSH key. When it is uploaded, add the link under the title in `README.md`.
