package com.payledger.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** GET /payments for the admin UI (ADR 0021): ADMIN sees all, a merchant only its own, newest first. */
class PaymentListTest extends PaymentApiSupport {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> ROWS = new ParameterizedTypeReference<>() { };

    private ResponseEntity<List<Map<String, Object>>> list(String query) {
        return http.exchange("/payments" + query, HttpMethod.GET, null, ROWS);
    }

    private HttpStatus status(String url) {
        return (HttpStatus) http.getForEntity(url, String.class).getStatusCode();
    }

    @Test
    void merchantSeesOnlyItsOwnPaymentsNewestFirstAndAdminSeesAll() {
        create("merchant-a", "a-1", PAYER, "1.00", "INR");
        create("merchant-a", "a-2", PAYER, "2.00", "INR");
        create("merchant-b", "b-1", PAYER, "3.00", "INR");

        TestAuth.as("merchant-a", "MERCHANT");
        List<Map<String, Object>> mine = list("").getBody();
        assertThat(mine).hasSize(2).extracting(p -> p.get("merchantId")).containsOnly("merchant-a");
        assertThat(mine.get(0).get("createdAt").toString()).isGreaterThanOrEqualTo(mine.get(1).get("createdAt").toString());

        TestAuth.as("root", "ADMIN");
        assertThat(list("").getBody()).hasSize(3);
        assertThat(list("?size=1").getBody()).hasSize(1);
    }

    @Test
    void statusFilterWorksAndUsersAreRefused() {
        create("merchant-a", "s-1", PAYER, "1.00", "INR");
        TestAuth.as("root", "ADMIN");

        assertThat(list("?status=AUTHORIZED").getBody()).extracting(p -> p.get("status")).containsOnly("AUTHORIZED");
        assertThat(list("?status=REFUNDED").getBody()).isEmpty();

        TestAuth.as("someone", "USER");
        assertThat(status("/payments")).isEqualTo(HttpStatus.FORBIDDEN);
        TestAuth.anonymous();
        assertThat(status("/payments")).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
