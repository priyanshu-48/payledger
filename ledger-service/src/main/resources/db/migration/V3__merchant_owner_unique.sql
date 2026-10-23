-- Payment events name the merchant by owner id (ADR 0014): at most one MERCHANT account per owner and currency,
-- so the lookup is unambiguous. Partial, because many wallets may legitimately share an owner id.
CREATE UNIQUE INDEX accounts_merchant_owner_currency_uq ON accounts (owner_id, currency) WHERE type = 'MERCHANT';
