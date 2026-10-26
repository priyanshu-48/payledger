package com.payledger.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/** SPEC 6.8: Bucket4j limit on POST /payments per client, answered with 429 and Retry-After. */
@TestPropertySource(properties = "payledger.ratelimit.payments-per-minute=3")
class PaymentRateLimitTest extends PaymentApiSupport {

    @Test
    void theFourthPaymentInAMinuteIsRefusedAndAnotherMerchantIsNotAffected() {
        for (int i = 1; i <= 3; i++) {
            assertThat(create("merchant-a", "a-" + i, PAYER, "5.00", "INR").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        ResponseEntity<Map> limited = create("merchant-a", "a-4", PAYER, "5.00", "INR");

        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(Integer.parseInt(limited.getHeaders().getFirst("Retry-After"))).isBetween(1, 60);
        assertThat(count("payments")).as("the refused request created nothing").isEqualTo(3);
        assertThat(create("merchant-b", "b-1", PAYER, "5.00", "INR").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TestAuth.as("merchant-a", "MERCHANT");
        assertThat(http.getForEntity("/payments/{id}", Map.class, java.util.UUID.randomUUID()).getStatusCode())
                .as("reads are not limited").isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
