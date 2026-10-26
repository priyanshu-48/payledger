-- Ledger outbox (ADR 0013, 0015): ledger.entry.posted events are written in the same transaction as the journal
-- entry. Same shape as payment-service's outbox; trace_parent carries the request's trace (ADR 0016).
CREATE TABLE outbox (
    id            UUID PRIMARY KEY,
    seq           BIGSERIAL   NOT NULL UNIQUE,
    aggregate_id  UUID        NOT NULL,
    event_type    TEXT        NOT NULL,
    payload       JSONB       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    claimed_until TIMESTAMPTZ,
    attempts      INT         NOT NULL DEFAULT 0,
    trace_parent  TEXT
);
CREATE INDEX outbox_unpublished_idx ON outbox (seq) WHERE published_at IS NULL;
CREATE INDEX outbox_unpublished_aggregate_idx ON outbox (aggregate_id, seq) WHERE published_at IS NULL;
