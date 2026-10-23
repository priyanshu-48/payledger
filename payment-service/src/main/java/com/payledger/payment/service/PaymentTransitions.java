package com.payledger.payment.service;

import com.payledger.payment.domain.Payment;
import com.payledger.payment.domain.PaymentEvent;
import com.payledger.payment.domain.PaymentEventRepository;
import com.payledger.payment.domain.PaymentRepository;
import com.payledger.payment.domain.PaymentStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two short transactions of the payment flow. A separate bean from PaymentService so every call
 * crosses the Spring proxy (self-invocation would skip @Transactional) and so no transaction is open
 * while PaymentService talks to the bank.
 */
@Service
public class PaymentTransitions {

    private final PaymentRepository payments;
    private final PaymentEventRepository events;

    public PaymentTransitions(PaymentRepository payments, PaymentEventRepository events) {
        this.payments = payments;
        this.events = events;
    }

    /** Inserts the payment as CREATED with its first history row. A duplicate (client, key) throws. */
    @Transactional
    public Payment insertNew(Payment payment) {
        payments.saveAndFlush(payment);
        events.save(new PaymentEvent(payment.getId(), null, PaymentStatus.CREATED, "created"));
        return payment;
    }

    /**
     * Moves the payment from {@code from} to {@code to} only if it is still in {@code from}, and records the
     * history row in the same transaction. Empty means another actor moved it first; the caller re-reads.
     * Pass null for bankRef to keep the existing one; failure reason is only replaced on FAILED.
     */
    @Transactional
    public Optional<Payment> transition(UUID id, PaymentStatus from, PaymentStatus to, String bankRef, String reason) {
        from.requireMoveTo(to);
        Payment current = payments.findById(id).orElseThrow(() -> new PaymentExceptions.PaymentNotFoundException(id));
        String newBankRef = bankRef != null ? bankRef : current.getBankRef();
        String newFailure = to == PaymentStatus.FAILED ? reason : current.getFailureReason();
        int rows = payments.transition(id, from, to, newBankRef, newFailure, Instant.now());
        if (rows == 0) {
            return Optional.empty();
        }
        events.save(new PaymentEvent(id, from, to, reason));
        return payments.findById(id);
    }
}
