package com.payledger.payment.service;

import java.util.UUID;

/** Payment-level failures; the web layer maps each to an HTTP status. */
public final class PaymentExceptions {

    private PaymentExceptions() {
    }

    public static class PaymentNotFoundException extends RuntimeException {
        public PaymentNotFoundException(UUID id) {
            super("payment not found: " + id);
        }
    }

    /** Same Idempotency-Key, different request body (422). */
    public static class IdempotencyKeyReuseException extends RuntimeException {
        public IdempotencyKeyReuseException(String key) {
            super("Idempotency-Key already used with a different request: " + key);
        }
    }

    /** Same Idempotency-Key while the first request is still being processed (409, retry later). */
    public static class RequestInProgressException extends RuntimeException {
        public RequestInProgressException(String key) {
            super("a request with Idempotency-Key " + key + " is still being processed; retry shortly");
        }
    }
}
