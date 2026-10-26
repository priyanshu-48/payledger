-- Bank reconciliation (SPEC 5, 6.7; ADR 0017).

-- One row per (file, period). The unique key is the run's identity: the same file for the same period is the
-- same run, which is what makes re-submitting it idempotent and a failed run restartable.
CREATE TABLE recon_runs (
    id                UUID PRIMARY KEY,
    file_name         TEXT        NOT NULL,
    file_sha256       VARCHAR(64) NOT NULL,
    period_start      TIMESTAMPTZ NOT NULL,
    period_end        TIMESTAMPTZ NOT NULL,
    status            VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at       TIMESTAMPTZ,
    matched           INT         NOT NULL DEFAULT 0,
    missing_in_ledger INT         NOT NULL DEFAULT 0,
    missing_in_bank   INT         NOT NULL DEFAULT 0,
    amount_mismatch   INT         NOT NULL DEFAULT 0,
    duplicate_in_bank INT         NOT NULL DEFAULT 0,
    error             TEXT,
    CONSTRAINT recon_runs_identity UNIQUE (file_sha256, period_start, period_end),
    CONSTRAINT recon_runs_period CHECK (period_end > period_start)
);

-- The bank file as read, one row per CSV line. Step 1 of the job fills it; line_no makes a restart idempotent.
CREATE TABLE recon_bank_rows (
    run_id       UUID        NOT NULL REFERENCES recon_runs (id),
    line_no      INT         NOT NULL,
    external_ref TEXT        NOT NULL,
    amount_minor BIGINT      NOT NULL,
    currency     VARCHAR(3)  NOT NULL,
    settled_at   TIMESTAMPTZ,
    PRIMARY KEY (run_id, line_no)
);
CREATE INDEX recon_bank_rows_ref_idx ON recon_bank_rows (run_id, external_ref);

-- The outcome per external_ref. Written by one set-based statement with ON CONFLICT DO NOTHING.
CREATE TABLE recon_items (
    id            BIGSERIAL PRIMARY KEY,
    run_id        UUID        NOT NULL REFERENCES recon_runs (id),
    external_ref  TEXT        NOT NULL,
    outcome       VARCHAR(24) NOT NULL CHECK (outcome IN
        ('MATCHED', 'MISSING_IN_LEDGER', 'MISSING_IN_BANK', 'AMOUNT_MISMATCH', 'DUPLICATE_IN_BANK')),
    ledger_amount BIGINT,
    bank_amount   BIGINT,
    detail        TEXT,
    CONSTRAINT recon_items_one_per_ref UNIQUE (run_id, external_ref)
);
CREATE INDEX recon_items_run_outcome_idx ON recon_items (run_id, outcome);

-- the ledger side of the comparison looks up captures by type and creation time
CREATE INDEX journal_entries_type_created_idx ON journal_entries (type, created_at);
