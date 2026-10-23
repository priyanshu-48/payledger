package com.payledger.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "payledger.mock-bank.decline-rate=1")
class PaymentDeclinedTest extends PaymentApiSupport {

    @Test
    void declinedPaymentIsFailedAndFinal() {
        ResponseEntity<Map> created = create("d-1", "75.00");
        String id = (String) created.getBody().get("id");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("status")).isEqualTo("FAILED");
        assertThat(created.getBody().get("failureReason")).asString().contains("declined");
        assertThat(post("/payments/" + id + "/capture").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(create("d-1", "75.00").getStatusCode()).isEqualTo(HttpStatus.OK); // replay sees the failure
    }
}
