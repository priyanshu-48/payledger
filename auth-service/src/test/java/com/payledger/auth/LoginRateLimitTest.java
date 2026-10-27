package com.payledger.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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

    /** As a proxy in front of the service would send it (the test client counts as an internal proxy: it is on loopback). */
    private HttpStatus loginFrom(String clientAddress) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", clientAddress);
        return (HttpStatus) http.exchange("/auth/token", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", "admin", "password", "wrong-password"), headers), Map.class).getStatusCode();
    }

    @Test
    void clientsBehindAProxyAreCountedByTheirForwardedAddressNotByTheProxy() {
        for (int i = 0; i < 3; i++) {
            assertThat(loginFrom("203.0.113.10")).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        assertThat(loginFrom("203.0.113.10")).as("this client used up its attempts").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(loginFrom("203.0.113.11")).as("another client behind the same proxy is unaffected").isEqualTo(HttpStatus.UNAUTHORIZED);
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
