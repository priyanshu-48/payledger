package com.payledger.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Password guessing is slowed down per client address (ADR 0019). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "auth.bootstrap-admin-password=bootstrap-admin-pw-123", "auth.rate-limit.login-per-minute=3"})
class LoginRateLimitTest extends AbstractPostgresTest {

    @Autowired TestRestTemplate http;

    private ResponseEntity<Map> login(String password) {
        return http.postForEntity("/auth/token", Map.of("username", "admin", "password", password), Map.class);
    }

    @Test
    void afterThreeAttemptsAMinuteLoginAnswers429EvenWithTheRightPassword() {
        for (int i = 0; i < 3; i++) {
            assertThat(login("wrong-password-" + i).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<Map> blocked = login("bootstrap-admin-pw-123");

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(Integer.parseInt(blocked.getHeaders().getFirst("Retry-After"))).isBetween(1, 60);
    }
}
