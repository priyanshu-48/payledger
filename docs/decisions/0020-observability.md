# ADR 0020: Metrics and the Grafana dashboard
- Status: accepted · Date: 2026-10-26
## Context
SPEC 6.9 / M8: payments by status, transfer latency histogram, outbox lag, consumer lag and reconciliation mismatches must be visible on one Grafana dashboard kept in the repository. Tracing was already done in M5 (ADR 0016); M8 adds the metrics, the dashboard and proof that the two agree.
## Decision
- **Metrics (Micrometer, scraped from `/actuator/prometheus`):**
  - `payments.by.status{status}` (payment-service): one gauge per status, always present, zero when empty. Read from the database (one `GROUP BY`, cached 1 s), so it is right after a restart. Counters would reset to zero on restart and cannot go down when a payment leaves a status.
  - `ledger.transfer.duration{strategy,outcome}` (ledger-service): a timer with a published histogram and fixed buckets from 10 ms to 1 s, so Prometheus can compute p50/p95/p99 across instances. `outcome` is `created`, `replayed` or the simple name of the exception that refused the request (a small fixed set), so refusals do not pollute the latency of real transfers.
  - `outbox.oldest.unpublished.age.seconds` and `outbox.unpublished` (both producing services, shared module): the age of the oldest unpublished row is the outbox lag. It is evaluated when scraped, with a query that uses the partial index on unpublished rows. A failing query gives NaN, never a failed scrape.
  - `kafka.consumer.group.lag{group,topic}` (ledger and notification): latest offset minus committed offset, asked of the broker through an Admin client and cached for 5 s. The consumer's own `records-lag` metric is not used because it only exists while the consumer is healthy and polling.
  - `recon.mismatches{outcome}` and `recon.runs{status}` (ledger-service): counted once, when a run finishes. An answer served from an earlier run (same file and period) counts nothing, so re-uploading does not inflate the totals.
  - All metrics carry the `application` tag; HTTP server request histograms are on, which also gives the 429 count from the rate limits (ADR 0019).
- **Dashboard:** `ops/grafana/dashboards/payledger.json`, loaded by file provisioning, datasource pinned to `uid: prometheus` so the JSON works on any Grafana. `ops/observability/dashboard-demo.sh` produces real traffic and failures and runs `check_dashboard.py`, which executes every panel query against Prometheus and fails on an error or an empty result. That is how a wrong metric name is caught, because Grafana itself only shows an empty panel.
- **Grafana allows anonymous Viewers** so the dashboard can be shown without sharing the admin password; editing and admin still need `GRAFANA_PASSWORD`. Actuator `health` and `prometheus` stay open inside the compose network (ADR 0019).
## Consequences
- The consumer-lag gauge lives inside the consumer's process: if that process is dead the series disappears instead of growing. Alerting should therefore also watch Prometheus' `up` (or absence of the series); the lag value is the signal for a consumer that is alive but slow. The demo shows the backlog draining after a restart.
- The status gauge issues one small query per scrape (at most once a second), and the lag gauge a few Admin calls every 5 s: fine at this scale, to be revisited with many partitions or consumer groups.
- Transfers posted by the Kafka consumer (payment captures) call the ledger service directly, so `ledger.transfer.duration` measures requests that arrive over HTTP. Capture throughput is visible as consumer lag and in the payments gauge.
## Alternatives rejected
Counters per status (reset on restart, cannot decrease); the Kafka client's lag metric (disappears with the consumer); an external exporter for lag (one more container for a few lines of code); a Grafana dashboard built by hand in the UI and exported later (not reviewable and not tested against the real metric names).
