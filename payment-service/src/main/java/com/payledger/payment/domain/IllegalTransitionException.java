package com.payledger.payment.domain;

public class IllegalTransitionException extends RuntimeException {

    public IllegalTransitionException(PaymentStatus from, PaymentStatus to) {
        super("illegal payment transition " + from + " -> " + to);
    }
}
