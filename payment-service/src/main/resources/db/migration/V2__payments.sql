-- Payments (SPEC section 5). Currency is varchar(3) + CHECK so Hibernate validation matches (see ledger V2).
-- Outbox and processed_events arrive with M4.

CREATE TABLE payments (
    id               UUID PRIMARY KEY,
    merchant_id      TEXT        NOT NULL,
    payer_account_id UUID        NOT NULL,
    amount_minor     BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency         VARCHAR(3)  NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status           VARCHAR(24) NOT NULL CHECK (status IN
        ('CREATED', 'AUTHORIZATION_UNKNOWN', 'AUTHORIZED', 'CAPTURED', 'SETTLED', 'FAILED', 'REFUNDED')),
    client_id        TEXT        NOT NULL,
    idempotency_key  TEXT        NOT NULL,
    request_hash     VARCHAR(64) NOT NULL,
    bank_ref         TEXT,
    failure_reason   TEXT,
    version          BIGINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT payments_idempotency UNIQUE (client_id, idempotency_key)
);
CREATE INDEX payments_status_updated_idx ON payments (status, updated_at);

CREATE TABLE payment_events (
    id          BIGSERIAL PRIMARY KEY,
    payment_id  UUID        NOT NULL REFERENCES payments (id),
    from_status VARCHAR(24),
    to_status   VARCHAR(24) NOT NULL,
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    reason      TEXT
);
CREATE INDEX payment_events_payment_idx ON payment_events (payment_id, id);

-- History is append-only, like the ledger.
CREATE FUNCTION forbid_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % not allowed', TG_TABLE_NAME, TG_OP;
END $$;

CREATE TRIGGER payment_events_append_only BEFORE UPDATE OR DELETE ON payment_events
    FOR EACH ROW EXECUTE FUNCTION forbid_change();
