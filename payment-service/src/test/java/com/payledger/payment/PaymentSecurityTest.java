package com.payledger.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.payledger.security.testing.TestTokens;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** SPEC 6.8 for payments: authentication, MERCHANT ownership, ADMIN reach, and the closed mock bank. */
class PaymentSecurityTest extends PaymentApiSupport {

    private ResponseEntity<Map> createWithToken(String token, String merchantIdInBody, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        headers.set("Idempotency-Key", key);
        Map<String, Object> body = Map.of("merchantId", merchantIdInBody, "payerAccountId", PAYER.toString(),
                "amount", new BigDecimal("12.00"), "currency", "INR");
        return http.exchange("/payments", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private String paymentOf(String merchant, String key) {
        return (String) create(merchant, key, PAYER, "12.00", "INR").getBody().get("id");
    }

    @Test
    void everythingExceptHealthNeedsAToken() {
        String id = paymentOf("merchant-a", "pay-1");
        TestAuth.anonymous();

        assertThat(createWithToken(null, "merchant-a", "k").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.getForEntity("/payments/{id}", Map.class, id).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.getForEntity("/payments/{id}/events", String.class, id).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post("/payments/" + id + "/capture").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post("/payments/" + id + "/refund").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.getForEntity("/actuator/health", Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(count("payments")).isEqualTo(1);
    }

    @Test
    void expiredForgedAndWrongIssuerTokensAreRejected() {
        for (String bad : List.of(TestTokens.expired("merchant-a", "MERCHANT"),
                TestTokens.signedByAnotherKey("merchant-a", "MERCHANT"),
                TestTokens.withIssuer("somebody-else", "merchant-a", "MERCHANT"), "garbage")) {
            assertThat(createWithToken(bad, "merchant-a", "k-bad").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(count("payments")).isZero();
    }

    @Test
    void onlyAMerchantCreatesPaymentsAndOnlyForItself() {
        assertThat(createWithToken(TestTokens.token("alice", "USER"), "alice", "u-1").getStatusCode())
                .as("a USER cannot create payments").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(createWithToken(TestTokens.token("root", "ADMIN"), "root", "adm-1").getStatusCode())
                .as("not even an ADMIN").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(createWithToken(TestTokens.token("merchant-a", "MERCHANT"), "merchant-b", "m-1").getStatusCode())
                .as("a merchant cannot create payments in another merchant's name").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(count("payments")).isZero();

        assertThat(createWithToken(TestTokens.token("merchant-a", "MERCHANT"), "merchant-a", "m-2").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT client_id FROM payments", String.class)).isEqualTo("merchant-a");
    }

    @Test
    void aMerchantCannotSeeOrOperateAnotherMerchantsPayment() {
        String id = paymentOf("merchant-a", "pay-1");
        TestAuth.as("merchant-b", "MERCHANT");

        assertThat(http.getForEntity("/payments/{id}", Map.class, id).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http.getForEntity("/payments/{id}/events", String.class, id).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/payments/" + id + "/capture").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/payments/" + id + "/refund").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http.getForEntity("/payments/{id}", Map.class, UUID.randomUUID()).getStatusCode())
                .as("a missing id looks the same as someone else's").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT status FROM payments", String.class)).isEqualTo("AUTHORIZED");

        TestAuth.as("merchant-a", "MERCHANT");
        assertThat(http.getForEntity("/payments/{id}", Map.class, id).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/payments/" + id + "/capture").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anAdminCanReadAndOperateOnAnyPaymentAndGetsA404ForAMissingOne() {
        String id = paymentOf("merchant-a", "pay-1");
        TestAuth.as("root", "ADMIN");

        assertThat(http.getForEntity("/payments/{id}", Map.class, id).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/payments/{id}/events", String.class, id).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/payments/" + id + "/capture").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/payments/{id}", Map.class, UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void idempotencyKeysBelongToTheAuthenticatedMerchant() {
        ResponseEntity<Map> a1 = create("merchant-a", "shared-key", PAYER, "12.00", "INR");
        ResponseEntity<Map> b1 = create("merchant-b", "shared-key", PAYER, "12.00", "INR");
        ResponseEntity<Map> a2 = create("merchant-a", "shared-key", PAYER, "12.00", "INR");

        assertThat(a1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(b1.getStatusCode()).as("same key, other merchant: a separate payment").isEqualTo(HttpStatus.CREATED);
        assertThat(a2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(a2.getBody().get("id")).isEqualTo(a1.getBody().get("id")).isNotEqualTo(b1.getBody().get("id"));
    }

    @Test
    void theMockBankIsClosedWithoutItsKey() {
        TestAuth.anonymous();

        assertThat(http.getForEntity("/mock-bank/authorizations/{ref}", Map.class, "x").getStatusCode())
                .as("no key").isEqualTo(HttpStatus.UNAUTHORIZED);
        HttpHeaders wrong = new HttpHeaders();
        wrong.set("X-Bank-Api-Key", "not-the-key");
        assertThat(http.exchange("/mock-bank/authorizations/{ref}", HttpMethod.GET, new HttpEntity<>(wrong), Map.class, "x")
                .getStatusCode()).as("wrong key").isEqualTo(HttpStatus.UNAUTHORIZED);
        HttpHeaders right = new HttpHeaders();
        right.set("X-Bank-Api-Key", "test-bank-key");
        assertThat(http.exchange("/mock-bank/authorizations/{ref}", HttpMethod.GET, new HttpEntity<>(right), Map.class, "x")
                .getStatusCode()).as("right key reaches the bank, which has never seen this reference").isEqualTo(HttpStatus.NOT_FOUND);
    }
}
