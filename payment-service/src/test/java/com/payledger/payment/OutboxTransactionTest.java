package com.payledger.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payledger.payment.domain.PaymentStatus;
import com.payledger.payment.service.PaymentTransitions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The outbox row is written in the same transaction as the state change: both happen or neither does. */
class OutboxTransactionTest extends PaymentApiSupport {

    @Autowired PaymentTransitions transitions;
    @Autowired PlatformTransactionManager txm;
    @Autowired ObjectMapper json;

    @Test
    void everyTransitionWritesExactlyOneOutboxRowInOrder() throws Exception {
        String id = (String) create("ob-1", "42.00").getBody().get("id");
        post("/payments/" + id + "/capture");
        post("/payments/" + id + "/refund");

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, aggregate_id, event_type, payload::text AS payload, "
                + "published_at FROM outbox ORDER BY seq");

        assertThat(rows).extracting(r -> r.get("event_type")).containsExactly(
                "payment.created", "payment.authorized", "payment.captured", "payment.refunded");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("aggregate_id").toString()).isEqualTo(id);
            assertThat(r.get("published_at")).isNull();
        });
        JsonNode captured = json.readTree((String) rows.get(2).get("payload"));
        assertThat(captured.get("eventId").asText()).isEqualTo(rows.get(2).get("id").toString());
        assertThat(captured.get("type").asText()).isEqualTo("payment.captured");
        assertThat(captured.get("amountMinor").asLong()).isEqualTo(4200);
        assertThat(captured.get("currency").asText()).isEqualTo("INR");
        assertThat(captured.get("fromStatus").asText()).isEqualTo("AUTHORIZED");
        assertThat(captured.get("toStatus").asText()).isEqualTo("CAPTURED");
    }

    @Test
    void aRollbackLeavesNeitherTheStateChangeNorTheOutboxRow() {
        UUID id = UUID.fromString((String) create("ob-2", "10.00").getBody().get("id")); // AUTHORIZED
        long outboxBefore = count("outbox");
        long eventsBefore = count("payment_events");

        // the surrounding transaction fails after transition() succeeded: it must undo everything it wrote
        assertThatThrownBy(() -> new TransactionTemplate(txm).executeWithoutResult(status -> {
            transitions.transition(id, PaymentStatus.AUTHORIZED, PaymentStatus.CAPTURED, null, "captured");
            throw new IllegalStateException("crash before commit");
        })).hasMessage("crash before commit");

        assertThat(http.getForObject("/payments/{id}", Map.class, id).get("status")).isEqualTo("AUTHORIZED");
        assertThat(count("outbox")).isEqualTo(outboxBefore);
        assertThat(count("payment_events")).isEqualTo(eventsBefore);
    }

    @Test
    void aTransitionThatLostTheRaceWritesNoOutboxRow() {
        UUID id = UUID.fromString((String) create("ob-3", "10.00").getBody().get("id")); // AUTHORIZED
        long outboxBefore = count("outbox");

        assertThat(transitions.transition(id, PaymentStatus.CREATED, PaymentStatus.FAILED, null, "stale")).isEmpty();

        assertThat(count("outbox")).isEqualTo(outboxBefore);
    }
}
