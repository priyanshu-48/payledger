package com.payledger.ledger.service;

import java.util.UUID;

/** Business-rule failures of the ledger; the web layer maps each to an HTTP status. */
public final class LedgerExceptions {

    private LedgerExceptions() {
    }

    /** Malformed request: non-positive amount, same account on both sides. */
    public static class InvalidTransferException extends RuntimeException {
        public InvalidTransferException(String message) {
            super(message);
        }
    }

    public static class AccountNotFoundException extends RuntimeException {
        public AccountNotFoundException(UUID id) {
            super("account not found: " + id);
        }
    }

    public static class AccountNotActiveException extends RuntimeException {
        public AccountNotActiveException(UUID id) {
            super("account is not active: " + id);
        }
    }

    /** The same Idempotency-Key was already used for a different transfer. */
    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String key) {
            super("Idempotency-Key already used with a different request: " + key);
        }
    }
}
