package com.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class AccountApiTest extends ApiTestSupport {

    @Test
    void newAccountStartsAtZero() {
        UUID wallet = createAccount("USER_WALLET", "INR");
        assertThat(balance(wallet)).isEqualByComparingTo("0.00");
        assertThat(count("account_balances")).isEqualTo(1);
    }

    @Test
    void unknownAccountIs404() {
        ResponseEntity<Map> r = http.getForEntity("/accounts/{id}/balance", Map.class, UUID.randomUUID());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void invalidCurrencyIs400() {
        ResponseEntity<Map> r = http.postForEntity("/accounts",
                Map.of("ownerId", "o", "type", "USER_WALLET", "currency", "rupee"), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
