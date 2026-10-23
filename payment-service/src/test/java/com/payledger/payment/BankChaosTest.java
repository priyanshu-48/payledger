package com.payledger.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.payledger.payment.domain.Payment;
import com.payledger.payment.domain.PaymentEvent;
import com.payledger.payment.domain.PaymentEventRepository;
import com.payledger.payment.domain.PaymentRepository;
import com.payledger.payment.domain.PaymentStatus;
import com.payledger.payment.service.StatusCheckJob;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * SPEC section 7 bank chaos: 1,000 payments against a bank that declines, times out and loses responses.
 * After the status job runs, nothing may be stuck and our records must agree with the bank's.
 * Latencies are scaled down from SPEC (50-2000 ms) so the test runs in seconds; the fault rates are the SPEC's.
 */
@TestPropertySource(properties = {
        "payledger.bank.timeout=PT0.25S",
        "payledger.mock-bank.latency-min-ms=5",
        "payledger.mock-bank.latency-max-ms=30",
        "payledger.mock-bank.decline-rate=0.05",
        "payledger.mock-bank.timeout-rate=0.03",
        "payledger.mock-bank.lost-response-rate=0.01",
        "payledger.mock-bank.stall=PT0.6S",
        "payledger.mock-bank.seed=42"})
class BankChaosTest extends PaymentApiSupport {

    private static final int PAYMENTS = 1_000;
    private static final int THREADS = 32;

    @Autowired PaymentRepository payments;
    @Autowired PaymentEventRepository events;
    @Autowired StatusCheckJob job;

    @Test
    void nothingIsStuckAndOurRecordsAgreeWithTheBank() throws Exception {
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < PAYMENTS; i++) {
            String key = "chaos-" + i;
            calls.add(() -> create(key, "10.00").getStatusCode().value());
        }
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        Map<Integer, Integer> httpStatuses = new TreeMap<>();
        try {
            for (Future<Integer> f : pool.invokeAll(calls)) {
                httpStatuses.merge(f.get(), 1, Integer::sum);
            }
        } finally {
            pool.shutdownNow();
        }

        long unknownBeforeSweep = payments.countByStatusIn(List.of(PaymentStatus.AUTHORIZATION_UNKNOWN));
        int sweeps = 0;
        while (payments.countByStatusIn(List.of(PaymentStatus.CREATED, PaymentStatus.AUTHORIZATION_UNKNOWN)) > 0
                && sweeps < 10) {
            job.runOnce();
            sweeps++;
        }

        List<Payment> all = payments.findAll();
        Map<String, Integer> outcomes = new TreeMap<>();
        for (Payment p : all) {
            boolean approvedAtBank = bank.lookup(p.getId().toString())
                    .map(a -> !a.status().equals("DECLINED")).orElse(false);
            assertThat(p.getStatus()).as("payment %s", p.getId()).isIn(PaymentStatus.AUTHORIZED, PaymentStatus.FAILED);
            // money safety: we say AUTHORIZED exactly when the bank really approved
            assertThat(p.getStatus() == PaymentStatus.AUTHORIZED)
                    .as("payment %s status %s, bank approved %s", p.getId(), p.getStatus(), approvedAtBank)
                    .isEqualTo(approvedAtBank);
            outcomes.merge(describe(p), 1, Integer::sum);
        }

        System.out.println("BANK CHAOS: http=" + httpStatuses + " unknownBeforeSweep=" + unknownBeforeSweep
                + " sweeps=" + sweeps + " outcomes=" + outcomes);
        assertThat(all).hasSize(PAYMENTS);
        assertThat(payments.countByStatusIn(List.of(PaymentStatus.CREATED, PaymentStatus.AUTHORIZATION_UNKNOWN)))
                .as("payments stuck after the status job").isZero();
        assertThat(unknownBeforeSweep).as("the chaos actually produced unknown outcomes").isPositive();
        assertThat(outcomes.getOrDefault("AUTHORIZED via status check", 0))
                .as("approved-but-response-lost payments recovered").isPositive();
    }

    /** Classifies by the reason recorded in the payment's last history row. */
    private String describe(Payment p) {
        List<PaymentEvent> history = events.findByPaymentIdOrderByIdAsc(p.getId());
        boolean viaStatusCheck = history.get(history.size() - 1).getReason().startsWith("status check");
        if (p.getStatus() == PaymentStatus.AUTHORIZED) {
            return viaStatusCheck ? "AUTHORIZED via status check" : "AUTHORIZED";
        }
        return viaStatusCheck ? "FAILED via status check (bank never saw it)" : "FAILED (declined)";
    }
}
