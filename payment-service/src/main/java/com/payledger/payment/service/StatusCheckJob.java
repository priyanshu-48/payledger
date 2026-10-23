package com.payledger.payment.service;

import com.payledger.payment.bank.BankClient;
import com.payledger.payment.bank.BankClient.Outcome;
import com.payledger.payment.domain.Payment;
import com.payledger.payment.domain.PaymentRepository;
import com.payledger.payment.domain.PaymentStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resolves payments whose outcome is not known (AUTHORIZATION_UNKNOWN after a bank timeout, or CREATED
 * left behind by a crash) by asking the bank. Only touches payments older than the grace period so it
 * never races a request that is still waiting for the bank (ADR 0012).
 */
@Component
public class StatusCheckJob {

    private static final Logger log = LoggerFactory.getLogger(StatusCheckJob.class);

    private final PaymentRepository payments;
    private final PaymentTransitions transitions;
    private final BankClient bank;
    private final Duration grace;

    public StatusCheckJob(PaymentRepository payments, PaymentTransitions transitions, BankClient bank,
                          @Value("${payledger.status-check.grace}") Duration grace) {
        this.payments = payments;
        this.transitions = transitions;
        this.bank = bank;
        this.grace = grace;
    }

    @Scheduled(initialDelayString = "${payledger.status-check.interval}",
            fixedDelayString = "${payledger.status-check.interval}")
    void scheduled() {
        try {
            int resolved = runOnce();
            if (resolved > 0) {
                log.info("status check resolved {} payments", resolved);
            }
        } catch (RuntimeException e) {
            log.warn("status check failed, will retry", e);
        }
    }

    /** One sweep; returns how many payments it resolved. */
    public int runOnce() {
        Instant cutoff = Instant.now().minus(grace);
        List<Payment> stuck = payments.findByStatusInAndUpdatedAtBefore(
                List.of(PaymentStatus.CREATED, PaymentStatus.AUTHORIZATION_UNKNOWN), cutoff);
        int resolved = 0;
        for (Payment payment : stuck) {
            Outcome outcome = bank.lookup(payment.getId());
            boolean moved = switch (outcome.result()) {
                case APPROVED -> move(payment, PaymentStatus.AUTHORIZED, outcome.bankRef(), "status check: bank approved");
                case DECLINED -> move(payment, PaymentStatus.FAILED, null, "status check: bank declined");
                case NOT_FOUND -> move(payment, PaymentStatus.FAILED, null, "status check: bank has no record");
                case UNKNOWN -> false; // bank unreachable now; try again next sweep
            };
            if (moved) {
                resolved++;
            }
        }
        return resolved;
    }

    private boolean move(Payment payment, PaymentStatus to, String bankRef, String reason) {
        return transitions.transition(payment.getId(), payment.getStatus(), to, bankRef, reason).isPresent();
    }
}
