package com.payledger.payment.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** The one place that says which status changes are legal (ADR 0010). */
public enum PaymentStatus {
    CREATED, AUTHORIZATION_UNKNOWN, AUTHORIZED, CAPTURED, SETTLED, FAILED, REFUNDED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = new EnumMap<>(PaymentStatus.class);

    static {
        ALLOWED.put(CREATED, EnumSet.of(AUTHORIZED, FAILED, AUTHORIZATION_UNKNOWN));
        ALLOWED.put(AUTHORIZATION_UNKNOWN, EnumSet.of(AUTHORIZED, FAILED));
        ALLOWED.put(AUTHORIZED, EnumSet.of(CAPTURED, FAILED));
        ALLOWED.put(CAPTURED, EnumSet.of(SETTLED, REFUNDED));
        ALLOWED.put(SETTLED, EnumSet.of(REFUNDED));
        ALLOWED.put(FAILED, EnumSet.noneOf(PaymentStatus.class));
        ALLOWED.put(REFUNDED, EnumSet.noneOf(PaymentStatus.class));
    }

    public boolean canMoveTo(PaymentStatus to) {
        return ALLOWED.get(this).contains(to);
    }

    /** Throws IllegalTransitionException unless this status may move to {@code to}. */
    public void requireMoveTo(PaymentStatus to) {
        if (!canMoveTo(to)) {
            throw new IllegalTransitionException(this, to);
        }
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /** Outcome not yet known: the status-check job owns these. */
    public boolean isUnresolved() {
        return this == CREATED || this == AUTHORIZATION_UNKNOWN;
    }
}
