package com.payledger.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.payledger.security.testing.LogCapture;
import com.payledger.security.testing.TestTokens;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * SPEC 6.8: no card numbers or secrets in logs, enforced. Covers a full payment flow (which includes the
 * server's own call to the bank with its API key), then invalid and forbidden requests carrying card numbers.
 */
class PaymentLogHygieneTest extends PaymentApiSupport {

    private static final List<String> CARD_NUMBERS = List.of("4111111111111111", "5500005555555559", "378282246310005");
    private static final String BANK_KEY = "test-bank-key";

    @Test
    void tokensBankKeyAndCardLookingValuesNeverReachTheLogsOrTheErrorResponses() {
        String token = TestTokens.token("merchant-a", "MERCHANT");
        try (LogCapture logs = new LogCapture()) {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Idempotency-Key", "hygiene-1");

            ResponseEntity<Map> created = create("merchant-a", "flow-1", PAYER, "20.00", "INR"); // calls the bank with its key
            String id = (String) created.getBody().get("id");
            post("/payments/" + id + "/capture");

            ResponseEntity<String> badCurrency = http.exchange("/payments", HttpMethod.POST, new HttpEntity<>(
                    "{\"merchantId\":\"merchant-a\",\"payerAccountId\":\"" + PAYER + "\",\"amount\":1.00,\"currency\":\""
                            + CARD_NUMBERS.get(0) + "\"}", headers), String.class);
            ResponseEntity<String> badMerchant = http.exchange("/payments", HttpMethod.POST, new HttpEntity<>(
                    "{\"merchantId\":\"" + CARD_NUMBERS.get(1) + "\",\"payerAccountId\":\"" + PAYER
                            + "\",\"amount\":1.00,\"currency\":\"INR\"}", headers), String.class);
            ResponseEntity<String> notJson = http.exchange("/payments", HttpMethod.POST, new HttpEntity<>(
                    "{\"payerAccountId\":\"" + CARD_NUMBERS.get(2) + "\"", headers), String.class);
            ResponseEntity<String> foreign = http.exchange("/payments/{id}", HttpMethod.GET,
                    new HttpEntity<>(bearer(TestTokens.token("merchant-b", "MERCHANT"))), String.class, id);
            HttpHeaders expired = bearer(TestTokens.expired("mallory", "ADMIN"));
            http.exchange("/payments/{id}", HttpMethod.GET, new HttpEntity<>(expired), String.class, id);

            String everything = logs.everything();
            assertThat(logs.size()).as("the check only means something if the app logged").isPositive();
            assertThat(everything).doesNotContain(token).doesNotContain("Bearer ").doesNotContain("eyJ").doesNotContain(BANK_KEY);
            CARD_NUMBERS.forEach(card -> assertThat(everything).as("logs must not contain %s", card).doesNotContain(card));
            for (ResponseEntity<String> response : List.of(badCurrency, badMerchant, notJson, foreign)) {
                CARD_NUMBERS.forEach(card -> assertThat(String.valueOf(response.getBody()))
                        .as("responses must not echo %s", card).doesNotContain(card));
                assertThat(response.getStatusCode().is4xxClientError()).isTrue();
            }
        }
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
