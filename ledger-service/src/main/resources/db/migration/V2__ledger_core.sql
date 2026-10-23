-- Ledger core (SPEC section 5). Currency columns are varchar(3) + CHECK instead of CHAR(3)
-- so Hibernate schema validation matches without bpchar quirks.

CREATE TABLE accounts (
    id         UUID PRIMARY KEY,
    owner_id   TEXT        NOT NULL,
    type       VARCHAR(16) NOT NULL CHECK (type IN ('USER_WALLET', 'MERCHANT', 'SETTLEMENT', 'FEES', 'SUSPENSE')),
    currency   VARCHAR(3)  NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status     VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'FROZEN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE journal_entries (
    id                UUID PRIMARY KEY,
    external_ref      TEXT        NOT NULL UNIQUE,
    type              VARCHAR(16) NOT NULL CHECK (type IN ('TRANSFER', 'PAYMENT_CAPTURE', 'REFUND', 'SETTLEMENT', 'REVERSAL')),
    reverses_entry_id UUID        REFERENCES journal_entries (id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE postings (
    id           UUID PRIMARY KEY,
    entry_id     UUID        NOT NULL REFERENCES journal_entries (id),
    account_id   UUID        NOT NULL REFERENCES accounts (id),
    direction    VARCHAR(6)  NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount_minor BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency     VARCHAR(3)  NOT NULL CHECK (currency ~ '^[A-Z]{3}$')
);
CREATE INDEX postings_entry_idx ON postings (entry_id);
CREATE INDEX postings_account_idx ON postings (account_id);

-- balance = credits - debits (ADR 0003). floor_zero is true for USER_WALLET (ADR 0004).
CREATE TABLE account_balances (
    account_id    UUID PRIMARY KEY REFERENCES accounts (id),
    balance_minor BIGINT      NOT NULL DEFAULT 0,
    floor_zero    BOOLEAN     NOT NULL,
    version       BIGINT      NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT balance_not_negative CHECK (NOT floor_zero OR balance_minor >= 0)
);

CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE audit_log (
    id      BIGSERIAL PRIMARY KEY,
    actor   TEXT        NOT NULL,
    action  TEXT        NOT NULL,
    target  TEXT        NOT NULL,
    at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    details JSONB
);

-- Append-only (ADR 0006). TRUNCATE is deliberately not trapped: tests need it; in production revoke it.
CREATE FUNCTION forbid_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % not allowed', TG_TABLE_NAME, TG_OP;
END $$;

CREATE TRIGGER journal_entries_append_only BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_change();
CREATE TRIGGER postings_append_only BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION forbid_change();
CREATE TRIGGER audit_log_append_only BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION forbid_change();

-- Balanced entries (ADR 0005): checked at COMMIT, once every posting of the entry is in.
CREATE FUNCTION check_entry_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    debits BIGINT; credits BIGINT; postings_count INT; currencies INT; mismatched INT;
BEGIN
    SELECT COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'CREDIT'), 0),
           COUNT(*), COUNT(DISTINCT currency)
      INTO debits, credits, postings_count, currencies
      FROM postings WHERE entry_id = NEW.entry_id;
    IF postings_count < 2 OR debits <> credits THEN
        RAISE EXCEPTION 'journal entry % is unbalanced (debits %, credits %, postings %)',
            NEW.entry_id, debits, credits, postings_count;
    END IF;
    IF currencies <> 1 THEN
        RAISE EXCEPTION 'journal entry % mixes currencies', NEW.entry_id;
    END IF;
    SELECT COUNT(*) INTO mismatched
      FROM postings p JOIN accounts a ON a.id = p.account_id
     WHERE p.entry_id = NEW.entry_id AND p.currency <> a.currency;
    IF mismatched > 0 THEN
        RAISE EXCEPTION 'journal entry % has a posting whose currency differs from its account', NEW.entry_id;
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER postings_balanced AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_entry_balanced();
