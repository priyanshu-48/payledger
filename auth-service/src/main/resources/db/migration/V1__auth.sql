-- Users of the platform (ADR 0018). Only a BCrypt hash is stored, never the password.
CREATE TABLE users (
    username      VARCHAR(64) PRIMARY KEY CHECK (username ~ '^[a-z0-9._-]{3,64}$'),
    password_hash TEXT        NOT NULL,
    role          VARCHAR(16) NOT NULL CHECK (role IN ('USER', 'MERCHANT', 'ADMIN')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Same append-only audit table as the ledger (ADR 0019).
CREATE TABLE audit_log (
    id      BIGSERIAL PRIMARY KEY,
    actor   TEXT        NOT NULL,
    action  TEXT        NOT NULL,
    target  TEXT        NOT NULL,
    at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    details JSONB
);

CREATE FUNCTION forbid_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % not allowed', TG_TABLE_NAME, TG_OP;
END $$;

CREATE TRIGGER audit_log_append_only BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION forbid_change();
