package com.payledger.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payledger.common.PaymentEventMessage;
import com.payledger.outbox.OutboxWriter;
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
 * while PaymentService talks to the bank. Each one writes the state change, the history row and the
 * outbox row together: all three commit or none do (ADR 0013).
 */
@Service
public class PaymentTransitions {

    private final PaymentRepository payments;
    private final PaymentEventRepository events;
    private final OutboxWriter outbox;
    private final ObjectMapper json;

    public PaymentTransitions(PaymentRepository payments, PaymentEventRepository events,
                              OutboxWriter outbox, ObjectMapper json) {
        this.payments = payments;
        this.events = events;
        this.outbox = outbox;
        this.json = json;
    }

    /** Inserts the payment as CREATED with its first history and outbox rows. A duplicate (client, key) throws. */
    @Transactional
    public Payment insertNew(Payment payment) {
        payments.saveAndFlush(payment);
        events.save(new PaymentEvent(payment.getId(), null, PaymentStatus.CREATED, "created"));
        writeOutbox(payment, null, PaymentStatus.CREATED);
        return payment;
    }

    /**
     * Moves the payment from {@code from} to {@code to} only if it is still in {@code from}, and records the
     * history and outbox rows in the same transaction. Empty means another actor moved it first; nothing is
     * written and the caller re-reads. Pass null for bankRef to keep the existing one; failure reason is only
     * replaced on FAILED.
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
        Payment updated = payments.findById(id).orElseThrow();
        writeOutbox(updated, from, to);
        return Optional.of(updated);
    }

    private void writeOutbox(Payment payment, PaymentStatus from, PaymentStatus to) {
        UUID eventId = UUID.randomUUID();
        PaymentEventMessage message = new PaymentEventMessage(eventId, PaymentEventMessage.typeFor(to.name()),
                payment.getId(), payment.getMerchantId(), payment.getPayerAccountId(), payment.getAmountMinor(),
                payment.getCurrency(), from == null ? null : from.name(), to.name(), Instant.now());
        try {
            outbox.write(eventId, payment.getId(), message.type(), json.writeValueAsString(message));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise " + message.type(), e);
        }
    }
}
