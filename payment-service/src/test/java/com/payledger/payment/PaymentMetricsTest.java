package com.payledger.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.payledger.outbox.OutboxStore;
import com.payledger.payment.domain.Payment;
import com.payledger.payment.domain.PaymentStatus;
import com.payledger.payment.service.PaymentTransitions;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The M8 gauges that read the database: payments by status and the outbox lag (ADR 0020). */
class PaymentMetricsTest extends PaymentApiSupport {

    @Autowired MeterRegistry registry;
    @Autowired PaymentTransitions transitions;
    @Autowired OutboxStore outbox;

    private double byStatus(PaymentStatus s) {
        return registry.get("payments.by.status").tag("status", s.name()).gauge().value();
    }

    private void insertPayment(PaymentStatus target) {
        Payment p = new Payment(UUID.randomUUID(), "m", PAYER, 1_000, "INR", "c", "k-" + UUID.randomUUID(), "h");
        transitions.insertNew(p);
        if (target == PaymentStatus.FAILED) {
            transitions.transition(p.getId(), PaymentStatus.CREATED, PaymentStatus.FAILED, null, "declined");
        }
    }

    @Test
    void paymentsByStatusCountsEachStatusAndReportsZeroForTheRest() {
        insertPayment(PaymentStatus.CREATED);
        insertPayment(PaymentStatus.CREATED);
        insertPayment(PaymentStatus.FAILED);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(byStatus(PaymentStatus.CREATED)).isEqualTo(2);
            assertThat(byStatus(PaymentStatus.FAILED)).isEqualTo(1);
            assertThat(byStatus(PaymentStatus.CAPTURED)).isZero();
        });
        assertThat(registry.find("payments.by.status").gauges()).hasSize(PaymentStatus.values().length);
    }

    @Test
    void outboxLagIsTheAgeOfTheOldestUnpublishedRowAndDropsToZeroWhenPublished() {
        assertThat(registry.get("outbox.oldest.unpublished.age.seconds").gauge().value()).isZero();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        for (UUID id : List.of(a, b)) {
            jdbc.update("INSERT INTO outbox (id, aggregate_id, event_type, payload, created_at) "
                    + "VALUES (?, ?, 't', '{}'::jsonb, now() - interval '2 minutes')", id, UUID.randomUUID());
        }

        assertThat(registry.get("outbox.oldest.unpublished.age.seconds").gauge().value()).isGreaterThanOrEqualTo(120);
        assertThat(registry.get("outbox.unpublished").gauge().value()).isEqualTo(2);

        outbox.markPublished(List.of(a, b));

        assertThat(registry.get("outbox.oldest.unpublished.age.seconds").gauge().value()).isZero();
        assertThat(registry.get("outbox.unpublished").gauge().value()).isZero();
    }
}
