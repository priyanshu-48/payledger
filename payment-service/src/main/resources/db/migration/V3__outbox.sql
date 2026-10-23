-- Transactional outbox (ADR 0013). SPEC 5 table plus seq (global order) and claimed_until (poller lease).
CREATE TABLE outbox (
    id            UUID PRIMARY KEY,
    seq           BIGSERIAL   NOT NULL UNIQUE,
    aggregate_id  UUID        NOT NULL,
    event_type    TEXT        NOT NULL,
    payload       JSONB       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    claimed_until TIMESTAMPTZ,
    attempts      INT         NOT NULL DEFAULT 0
);

-- the poller only ever looks at unpublished rows, oldest first, and per payment
CREATE INDEX outbox_unpublished_idx ON outbox (seq) WHERE published_at IS NULL;
CREATE INDEX outbox_unpublished_aggregate_idx ON outbox (aggregate_id, seq) WHERE published_at IS NULL;
