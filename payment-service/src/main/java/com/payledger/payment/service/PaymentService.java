package com.payledger.payment.service;

import com.payledger.common.Money;
import com.payledger.payment.bank.BankClient;
import com.payledger.payment.bank.BankClient.Outcome;
import com.payledger.payment.domain.Payment;
import com.payledger.payment.domain.PaymentEvent;
import com.payledger.payment.domain.PaymentEventRepository;
import com.payledger.payment.domain.PaymentRepository;
import com.payledger.payment.domain.PaymentStatus;
import com.payledger.payment.service.PaymentExceptions.IdempotencyKeyReuseException;
import com.payledger.payment.service.PaymentExceptions.PaymentNotFoundException;
import com.payledger.payment.service.PaymentExceptions.RequestInProgressException;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a payment across short database transactions and bank calls. Deliberately NOT
 * transactional: no connection is held while waiting on the bank (ADR 0012).
 */
@Service
public class PaymentService {

    public record CreatePayment(String clientId, String idempotencyKey, String merchantId, UUID payerAccountId,
                                Money amount) {
    }

    /** {@code created} is false when the call was answered from an earlier request with the same key. */
    public record Created(Payment payment, boolean created) {
    }

    private final PaymentRepository payments;
    private final PaymentEventRepository events;
    private final PaymentTransitions transitions;
    private final BankClient bank;

    public PaymentService(PaymentRepository payments, PaymentEventRepository events,
                          PaymentTransitions transitions, BankClient bank) {
        this.payments = payments;
        this.events = events;
        this.transitions = transitions;
        this.bank = bank;
    }

    public Created create(CreatePayment cmd) {
        String hash = PaymentRequestHash.of(cmd.merchantId(), cmd.payerAccountId(), cmd.amount().minor(),
                cmd.amount().currency());
        Payment candidate = new Payment(UUID.randomUUID(), cmd.merchantId(), cmd.payerAccountId(),
                cmd.amount().minor(), cmd.amount().currency(), cmd.clientId(), cmd.idempotencyKey(), hash);
        try {
            transitions.insertNew(candidate);
        } catch (DataIntegrityViolationException duplicate) {
            // The unique (client, key) constraint decided the race; the winner's row is committed by now.
            Payment existing = payments.findByClientIdAndIdempotencyKey(cmd.clientId(), cmd.idempotencyKey())
                    .orElseThrow(() -> duplicate);
            if (!existing.getRequestHash().equals(hash)) {
                throw new IdempotencyKeyReuseException(cmd.idempotencyKey());
            }
            if (existing.getStatus() == PaymentStatus.CREATED) {
                throw new RequestInProgressException(cmd.idempotencyKey());
            }
            return new Created(existing, false);
        }

        // We own this payment: ask the bank, with no database transaction open.
        Outcome outcome = bank.authorize(candidate.getId(), cmd.amount().minor(), cmd.amount().currency());
        Payment result = switch (outcome.result()) {
            case APPROVED -> move(candidate.getId(), PaymentStatus.CREATED, PaymentStatus.AUTHORIZED,
                    outcome.bankRef(), "bank approved");
            case DECLINED -> move(candidate.getId(), PaymentStatus.CREATED, PaymentStatus.FAILED,
                    null, "bank declined: " + outcome.reason());
            // A timeout or error does not say what the bank did. Never assume: park it for the status check.
            default -> move(candidate.getId(), PaymentStatus.CREATED, PaymentStatus.AUTHORIZATION_UNKNOWN,
                    null, "bank did not answer (" + outcome.reason() + ")");
        };
        return new Created(result, true);
    }

    public Payment get(UUID id) {
        return payments.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }

    public List<PaymentEvent> history(UUID id) {
        get(id);
        return events.findByPaymentIdOrderByIdAsc(id);
    }

    /** AUTHORIZED -> CAPTURED. Repeating it on a captured payment is a no-op. */
    public Payment capture(UUID id) {
        Payment payment = get(id);
        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            return payment;
        }
        payment.getStatus().requireMoveTo(PaymentStatus.CAPTURED);
        bank.capture(id);
        return move(id, payment.getStatus(), PaymentStatus.CAPTURED, null, "captured");
    }

    /** CAPTURED or SETTLED -> REFUNDED (full refund). Repeating it on a refunded payment is a no-op. */
    public Payment refund(UUID id) {
        Payment payment = get(id);
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            return payment;
        }
        payment.getStatus().requireMoveTo(PaymentStatus.REFUNDED);
        bank.refund(id);
        return move(id, payment.getStatus(), PaymentStatus.REFUNDED, null, "refunded");
    }

    /** Applies the conditional transition; if someone else got there first, returns what they left. */
    private Payment move(UUID id, PaymentStatus from, PaymentStatus to, String bankRef, String reason) {
        return transitions.transition(id, from, to, bankRef, reason).orElseGet(() -> get(id));
    }
}
