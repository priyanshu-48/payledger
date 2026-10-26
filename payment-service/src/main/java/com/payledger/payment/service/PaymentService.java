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
import com.payledger.security.Caller;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a payment across short database transactions and bank calls. Deliberately NOT
 * transactional: no connection is held while waiting on the bank (ADR 0012). Every public method takes
 * the {@link Caller} and checks ownership first (ADR 0019).
 */
@Service
public class PaymentService {

    /** The merchant is the authenticated caller; it is also the idempotency scope (ADR 0012, 0018). */
    public record CreatePayment(String idempotencyKey, String merchantId, UUID payerAccountId, Money amount) {
    }

    /** {@code created} is false when the call was answered from an earlier request with the same key. */
    public record Created(Payment payment, boolean created) {
    }

    private final PaymentRepository payments;
    private final PaymentEventRepository events;
    private final PaymentTransitions transitions;
    private final BankClient bank;
    private final PaymentAccess access;

    public PaymentService(PaymentRepository payments, PaymentEventRepository events, PaymentTransitions transitions,
                          BankClient bank, PaymentAccess access) {
        this.payments = payments;
        this.events = events;
        this.transitions = transitions;
        this.bank = bank;
        this.access = access;
    }

    public Created create(Caller caller, CreatePayment cmd) {
        access.requireMayCreate(caller, cmd.merchantId());
        String clientId = caller.subject();
        String hash = PaymentRequestHash.of(cmd.merchantId(), cmd.payerAccountId(), cmd.amount().minor(),
                cmd.amount().currency());
        Payment candidate = new Payment(UUID.randomUUID(), cmd.merchantId(), cmd.payerAccountId(),
                cmd.amount().minor(), cmd.amount().currency(), clientId, cmd.idempotencyKey(), hash);
        try {
            transitions.insertNew(candidate);
        } catch (DataIntegrityViolationException duplicate) {
            // The unique (client, key) constraint decided the race; the winner's row is committed by now.
            Payment existing = payments.findByClientIdAndIdempotencyKey(clientId, cmd.idempotencyKey())
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

    /** ADMIN sees every payment, a MERCHANT only its own; anybody else is refused. Newest first. */
    public List<Payment> list(Caller caller, PaymentStatus status, int page, int size) {
        String merchant = access.merchantScope(caller);
        return payments.search(merchant, status == null ? null : status.name(), PageRequest.of(page, size));
    }

    public Payment get(Caller caller, UUID id) {
        return authorised(caller, id);
    }

    public List<PaymentEvent> history(Caller caller, UUID id) {
        authorised(caller, id);
        return events.findByPaymentIdOrderByIdAsc(id);
    }

    /** AUTHORIZED -> CAPTURED. Repeating it on a captured payment is a no-op. */
    public Payment capture(Caller caller, UUID id) {
        Payment payment = authorised(caller, id);
        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            return payment;
        }
        payment.getStatus().requireMoveTo(PaymentStatus.CAPTURED);
        bank.capture(id);
        return move(id, payment.getStatus(), PaymentStatus.CAPTURED, null, "captured");
    }

    /** CAPTURED or SETTLED -> REFUNDED (full refund). Repeating it on a refunded payment is a no-op. */
    public Payment refund(Caller caller, UUID id) {
        Payment payment = authorised(caller, id);
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            return payment;
        }
        payment.getStatus().requireMoveTo(PaymentStatus.REFUNDED);
        bank.refund(id);
        return move(id, payment.getStatus(), PaymentStatus.REFUNDED, null, "refunded");
    }

    /**
     * Loads the payment and checks the caller may touch it. A caller who is not an ADMIN gets the same refusal
     * for a missing payment as for somebody else's, so ids cannot be probed.
     */
    private Payment authorised(Caller caller, UUID id) {
        Payment payment = payments.findById(id).orElse(null);
        if (payment == null) {
            throw caller.isAdmin() ? new PaymentNotFoundException(id) : access.denied();
        }
        access.requireMayAccess(caller, payment);
        return payment;
    }

    /** Applies the conditional transition; if someone else got there first, returns what they left. */
    private Payment move(UUID id, PaymentStatus from, PaymentStatus to, String bankRef, String reason) {
        return transitions.transition(id, from, to, bankRef, reason)
                .orElseGet(() -> payments.findById(id).orElseThrow(() -> new PaymentNotFoundException(id)));
    }
}
