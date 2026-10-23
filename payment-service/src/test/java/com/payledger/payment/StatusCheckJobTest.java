package com.payledger.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.payledger.payment.bank.BankClient;
import com.payledger.payment.bank.BankDtos.AuthorizeRequest;
import com.payledger.payment.domain.Payment;
import com.payledger.payment.domain.PaymentRepository;
import com.payledger.payment.domain.PaymentStatus;
import com.payledger.payment.service.PaymentTransitions;
import com.payledger.payment.service.StatusCheckJob;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The status job resolves unknown outcomes by asking the bank, never by guessing. */
class StatusCheckJobTest extends PaymentApiSupport {

    @Autowired PaymentRepository payments;
    @Autowired PaymentTransitions transitions;
    @Autowired StatusCheckJob job;
    @Autowired BankClient bankClient;

    /** A payment parked as AUTHORIZATION_UNKNOWN; the bank approved it only if {@code bankApproved}. */
    private UUID unknownPayment(boolean bankApproved) throws InterruptedException {
        Payment p = new Payment(UUID.randomUUID(), "m", PAYER, 1_000, "INR", "c", "k-" + UUID.randomUUID(), "h");
        transitions.insertNew(p);
        if (bankApproved) {
            bank.authorize(new AuthorizeRequest(p.getId().toString(), 1_000, "INR"));
        }
        transitions.transition(p.getId(), PaymentStatus.CREATED, PaymentStatus.AUTHORIZATION_UNKNOWN, null, "timeout");
        return p.getId();
    }

    private PaymentStatus status(UUID id) {
        return payments.findById(id).orElseThrow().getStatus();
    }

    @Test
    void unknownPaymentApprovedAtTheBankBecomesAuthorized() throws Exception {
        UUID id = unknownPayment(true);

        assertThat(job.runOnce()).isEqualTo(1);

        assertThat(status(id)).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(payments.findById(id).orElseThrow().getBankRef()).startsWith("BANK-");
        assertThat(job.runOnce()).as("nothing left to do").isZero();
    }

    @Test
    void unknownPaymentTheBankNeverSawBecomesFailed() throws Exception {
        UUID id = unknownPayment(false);

        assertThat(job.runOnce()).isEqualTo(1);

        assertThat(status(id)).isEqualTo(PaymentStatus.FAILED);
        assertThat(payments.findById(id).orElseThrow().getFailureReason()).contains("no record");
    }

    @Test
    void staleCreatedPaymentLeftByACrashIsResolvedToo() throws Exception {
        Payment crashed = new Payment(UUID.randomUUID(), "m", PAYER, 1_000, "INR", "c", "k-crash", "h");
        transitions.insertNew(crashed); // the app died right after this, before calling the bank
        bank.authorize(new AuthorizeRequest(crashed.getId().toString(), 1_000, "INR")); // ...but the call had gone out

        job.runOnce();

        assertThat(status(crashed.getId())).isEqualTo(PaymentStatus.AUTHORIZED);
    }

    @Test
    void paymentsInsideTheGracePeriodAreLeftAlone() throws Exception {
        UUID id = unknownPayment(true);
        StatusCheckJob patient = new StatusCheckJob(payments, transitions, bankClient, Duration.ofHours(1));

        assertThat(patient.runOnce()).isZero();

        assertThat(status(id)).isEqualTo(PaymentStatus.AUTHORIZATION_UNKNOWN);
    }
}
