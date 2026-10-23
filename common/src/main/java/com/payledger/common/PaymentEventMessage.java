package com.payledger.common;

import java.time.Instant;
import java.util.UUID;

/**
 * Contract between payment-service (outbox) and ledger-service (consumer), sent as JSON on {@link #TOPIC}.
 * {@code eventId} is the outbox row id and the consumer's dedup key.
 */
public record PaymentEventMessage(UUID eventId, String type, UUID paymentId, String merchantId, UUID payerAccountId,
                                  long amountMinor, String currency, String fromStatus, String toStatus,
                                  Instant occurredAt) {

    public static final String TOPIC = "payments.events";
    public static final String DEAD_LETTER_TOPIC = TOPIC + ".DLT";
    public static final String CAPTURED = "payment.captured";
    public static final String REFUNDED = "payment.refunded";

    /** "payment." + the new status in lower case, e.g. payment.captured. */
    public static String typeFor(String toStatus) {
        return "payment." + toStatus.toLowerCase();
    }
}
