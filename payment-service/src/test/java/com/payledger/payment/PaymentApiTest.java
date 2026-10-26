package com.payledger.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.payledger.payment.domain.PaymentStatus;
import com.payledger.payment.service.PaymentTransitions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class PaymentApiTest extends PaymentApiSupport {

    @Autowired PaymentTransitions transitions;

    @Test
    void happyPathWithFullHistory() {
        ResponseEntity<Map> created = create("k-1", "500.00");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("status")).isEqualTo("AUTHORIZED");
        assertThat(created.getBody().get("bankRef")).asString().startsWith("BANK-");
        String id = (String) created.getBody().get("id");

        assertThat(post("/payments/" + id + "/capture").getBody().get("status")).isEqualTo("CAPTURED");
        assertThat(post("/payments/" + id + "/refund").getBody().get("status")).isEqualTo("REFUNDED");

        List<Map<String, Object>> events = http.getForObject("/payments/{id}/events", List.class, id);
        assertThat(events).extracting(e -> e.get("from") + "->" + e.get("to"))
                .containsExactly("null->CREATED", "CREATED->AUTHORIZED", "AUTHORIZED->CAPTURED", "CAPTURED->REFUNDED");
        assertThat(http.getForObject("/payments/{id}", Map.class, id).get("amount")).isEqualTo(500.0);
    }

    @Test
    void replayWithSameKeyAndBodyReturnsTheSamePaymentWithoutAnotherBankCall() {
        ResponseEntity<Map> first = create("k-2", "10.00");
        long bankCalls = bank.authorizeCalls();

        ResponseEntity<Map> second = create("k-2", "10.00");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(count("payments")).isEqualTo(1);
        assertThat(bank.authorizeCalls()).isEqualTo(bankCalls);
    }

    @Test
    void sameKeyWithDifferentBodyIs422() {
        create("k-3", "10.00");
        ResponseEntity<Map> r = create("k-3", "11.00");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(count("payments")).isEqualTo(1);
    }

    @Test
    void keysAreScopedPerClient() {
        ResponseEntity<Map> a = create("merchant-a", "shared", PAYER, "5.00", "INR");
        ResponseEntity<Map> b = create("merchant-b", "shared", PAYER, "5.00", "INR");
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(a.getBody().get("id")).isNotEqualTo(b.getBody().get("id"));
    }

    @Test
    void illegalMovesAreRejectedAndRepeatsAreHarmless() {
        String id = (String) create("k-4", "20.00").getBody().get("id");

        assertThat(post("/payments/" + id + "/refund").getStatusCode()).isEqualTo(HttpStatus.CONFLICT); // not captured yet
        assertThat(post("/payments/" + id + "/capture").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/payments/" + id + "/capture").getStatusCode()).isEqualTo(HttpStatus.OK);       // repeat = no-op
        assertThat(post("/payments/" + id + "/refund").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/payments/" + id + "/refund").getStatusCode()).isEqualTo(HttpStatus.OK);        // repeat = no-op
        assertThat(post("/payments/" + id + "/capture").getStatusCode()).isEqualTo(HttpStatus.CONFLICT); // terminal
        assertThat(count("payment_events")).isEqualTo(4);
    }

    @Test
    void badRequestsAndUnknownIds() {
        assertThat(create(TestAuth.DEFAULT_MERCHANT, null, PAYER, "5.00", "INR").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(create(TestAuth.DEFAULT_MERCHANT, "k-5", PAYER, "0.00", "INR").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(create(TestAuth.DEFAULT_MERCHANT, "k-6", PAYER, "1.005", "INR").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(create(TestAuth.DEFAULT_MERCHANT, "k-7", PAYER, "1.00", "rupee").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/payments/{id}", Map.class, UUID.randomUUID()).getStatusCode())
                .as("a merchant is not told whether an id exists").isEqualTo(HttpStatus.FORBIDDEN);
        TestAuth.as("root", "ADMIN");
        assertThat(http.getForEntity("/payments/{id}", Map.class, UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(count("payments")).isZero();
    }

    @Test
    void conditionalTransitionDoesNothingWhenThePaymentMovedOnAlready() {
        UUID id = UUID.fromString((String) create("k-8", "5.00").getBody().get("id")); // now AUTHORIZED
        long events = count("payment_events");

        // we believe it is still CREATED: the conditional update must match zero rows and write no history
        assertThat(transitions.transition(id, PaymentStatus.CREATED, PaymentStatus.FAILED, null, "stale")).isEmpty();

        assertThat(count("payment_events")).isEqualTo(events);
        assertThat(http.getForObject("/payments/{id}", Map.class, id).get("status")).isEqualTo("AUTHORIZED");
    }
}
