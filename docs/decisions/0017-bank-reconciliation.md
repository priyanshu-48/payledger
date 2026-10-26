# ADR 0017: Bank reconciliation with Spring Batch
- Status: accepted · Date: 2026-10-26
## Context
SPEC 6.7: a restartable Spring Batch job reads the bank's settlement CSV, matches on `external_ref`, reports MATCHED, MISSING_IN_LEDGER, MISSING_IN_BANK or AMOUNT_MISMATCH per row in `recon_items`, and can be re-run on the same file without duplicating anything.
## Decision
- **Scope:** the file is compared with ledger `PAYMENT_CAPTURE` entries (refunds are out of scope for now). CSV columns: `external_ref,amount_minor,currency,settled_at`; `external_ref` is the reference we gave the bank at capture (`payment-capture:<paymentId>`). A run has a period `[periodStart, periodEnd)`: captures created in the period but absent from the file are MISSING_IN_BANK; captures outside the period are never flagged.
- **Shape (load, then compare in SQL):** step 1 is chunk-oriented and restartable (`FlatFileItemReader` -> staging table `recon_bank_rows`, primary key `(run_id, line_no)`, `ON CONFLICT DO NOTHING`); step 2 is one set-based statement that joins staging with the ledger and inserts `recon_items` (unique `(run_id, external_ref)`, `ON CONFLICT DO NOTHING`); step 3 writes the counters and status on `recon_runs`. The comparison is a join, which the database does better than a lookup per row, and both writes are idempotent, so a restart cannot duplicate or lose rows.
- **A fifth outcome, DUPLICATE_IN_BANK**, not in the SPEC list: a bank that lists one reference twice is a real settlement defect and would otherwise violate the one-item-per-reference rule. Precedence: no ledger entry -> MISSING_IN_LEDGER; more than one bank row -> DUPLICATE_IN_BANK; amount or currency differs -> AMOUNT_MISMATCH; otherwise MATCHED.
- **Malformed rows fail the job** (no silent skipping); the run is FAILED with the parse error, and the corrected file is a new run.
- **Identity and restart:** job instance identity = file SHA-256 + period (identifying job parameters); run id and stored file path are non-identifying. The same file and period: a COMPLETED run is returned as is (HTTP 200); a FAILED run is restarted by the same call and resumes from the last committed chunk. Enforced by `UNIQUE(file_sha256, period_start, period_end)` on `recon_runs`.
- Spring Batch's metadata tables are created by Flyway (DDL copied from the jar); the job runs synchronously inside the upload request.
## Consequences
The job only reports. Fixing a mismatch is a human decision followed by a reversal or adjusting entry, never an edit (CLAUDE.md). A stuck RUNNING run after a crash needs an operator; there is no automatic recovery yet.
## Alternatives rejected
One lookup per CSV row (many round trips, harder to make idempotent); a processor that compares in memory (loses the join and the restart story); skipping bad rows (hides defects in the bank file).
