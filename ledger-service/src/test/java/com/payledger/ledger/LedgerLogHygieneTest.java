package com.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.payledger.security.testing.LogCapture;
import com.payledger.security.testing.TestTokens;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** SPEC 6.8: no card numbers or secrets in logs, enforced. Also checks error responses do not echo what was sent. */
class LedgerLogHygieneTest extends ApiTestSupport {

    private static final List<String> CARD_NUMBERS = List.of("4111111111111111", "5500005555555559", "378282246310005");

    @Test
    void tokensAndCardLookingValuesNeverReachTheLogsOrTheErrorResponses() {
        String aliceToken = TestTokens.token("alice", "USER");
        UUID wallet = createAccount("USER_WALLET", "INR");
        try (LogCapture logs = new LogCapture()) {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(aliceToken);
            headers.setContentType(MediaType.APPLICATION_JSON);

            // valid, forbidden, malformed and invalid calls, with card numbers where a client could put them
            ResponseEntity<Map> forbidden = http.exchange("/accounts/{id}/balance", HttpMethod.GET, new HttpEntity<>(headers), Map.class, wallet);
            ResponseEntity<String> badCurrency = http.exchange("/accounts", HttpMethod.POST, new HttpEntity<>(
                    "{\"ownerId\":\"alice\",\"type\":\"USER_WALLET\",\"currency\":\"" + CARD_NUMBERS.get(0) + "\"}", headers), String.class);
            ResponseEntity<String> badOwner = http.exchange("/accounts", HttpMethod.POST, new HttpEntity<>(
                    "{\"ownerId\":\"" + CARD_NUMBERS.get(1) + "\",\"type\":\"USER_WALLET\",\"currency\":\"INR\"}", headers), String.class);
            ResponseEntity<String> notJson = http.exchange("/transfers", HttpMethod.POST, new HttpEntity<>(
                    "{\"fromAccountId\":\"" + CARD_NUMBERS.get(2) + "\"", withKey(headers)), String.class);
            ResponseEntity<String> badId = http.exchange("/accounts/not-a-uuid/balance", HttpMethod.GET,
                    new HttpEntity<>(headers), String.class); // (card numbers never belong in URLs, which are logged by design)
            HttpHeaders anonymous = new HttpHeaders();
            anonymous.set("Authorization", "Bearer " + TestTokens.expired("mallory", "ADMIN"));
            http.exchange("/accounts/{id}/balance", HttpMethod.GET, new HttpEntity<>(anonymous), String.class, wallet);

            String everything = logs.everything();
            assertThat(logs.size()).as("the check only means something if the app logged").isPositive();
            assertThat(everything).doesNotContain(aliceToken).doesNotContain("Bearer ").doesNotContain("eyJ");
            CARD_NUMBERS.forEach(card -> assertThat(everything).as("logs must not contain %s", card).doesNotContain(card));
            for (ResponseEntity<?> response : List.of(forbidden, badCurrency, badOwner, notJson, badId)) {
                String body = String.valueOf(response.getBody());
                CARD_NUMBERS.forEach(card -> assertThat(body).as("responses must not echo %s", card).doesNotContain(card));
                assertThat(response.getStatusCode().is4xxClientError()).isTrue();
            }
        }
    }

    private static HttpHeaders withKey(HttpHeaders base) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(base);
        headers.set("Idempotency-Key", "hygiene-1");
        return headers;
    }
}
