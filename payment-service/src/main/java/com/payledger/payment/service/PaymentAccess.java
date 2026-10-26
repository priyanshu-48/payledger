package com.payledger.payment.service;

import com.payledger.payment.domain.Payment;
import com.payledger.security.Caller;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Who may touch which payment (ADR 0019), decided in the service layer so it holds for every entry point.
 * Only a MERCHANT creates payments, and only for itself ({@code merchantId} must equal the token's subject).
 * A merchant may read, capture and refund its own payments; an ADMIN may read and operate on any.
 */
@Component
public class PaymentAccess {

    private static final String DENIED = "you are not allowed to do this";

    public void requireMayCreate(Caller caller, String merchantId) {
        if (!caller.hasRole(Caller.MERCHANT) || !caller.subject().equals(merchantId)) {
            throw new AccessDeniedException(DENIED);
        }
    }

    public void requireMayAccess(Caller caller, Payment payment) {
        if (caller.isAdmin()) {
            return;
        }
        if (!caller.hasRole(Caller.MERCHANT) || !caller.subject().equals(payment.getMerchantId())) {
            throw new AccessDeniedException(DENIED);
        }
    }

    /** What a non-admin hears for a payment that does not exist: the same refusal as for someone else's. */
    public AccessDeniedException denied() {
        return new AccessDeniedException(DENIED);
    }
}
