package com.payledger.payment;

import com.payledger.payment.bank.MockBank;
import com.payledger.security.testing.TestJwtConfiguration;
import com.payledger.security.testing.TestTokens;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real HTTP, real Postgres, and the mock bank running inside the same app. Defaults: fast, fault-free bank,
 * no scheduled sweeps (tests call the status job themselves). Subclasses override with @TestPropertySource.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "payledger.bank.base-url=http://localhost:${local.server.port}/mock-bank",
        "payledger.bank.timeout=PT2S",
        "payledger.mock-bank.latency-min-ms=1",
        "payledger.mock-bank.latency-max-ms=5",
        "payledger.mock-bank.decline-rate=0",
        "payledger.mock-bank.timeout-rate=0",
        "payledger.mock-bank.lost-response-rate=0",
        "payledger.mock-bank.stall=PT1S",
        "payledger.mock-bank.seed=7",
        "payledger.status-check.grace=PT0S",
        "payledger.status-check.interval=PT1H",
        "payledger.bank.api-key=test-bank-key",
        "payledger.ratelimit.payments-per-minute=100000"})
@Import(TestJwtConfiguration.class)
abstract class PaymentApiSupport extends AbstractPostgresTest {

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockBank bank;

    @BeforeEach
    void cleanPayments() {
        TestAuth.install(http);
        TestAuth.asDefaultMerchant();
        jdbc.execute("TRUNCATE payment_events, payments, outbox CASCADE");
    }

    /** Creates a payment as the MERCHANT {@code subject}; the merchant id in the body is the subject itself. */
    ResponseEntity<Map> create(String subject, String key, UUID payer, String amount, String currency) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.token(subject, "MERCHANT"));
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        Map<String, Object> body = Map.of("merchantId", subject, "payerAccountId", payer.toString(),
                "amount", new BigDecimal(amount), "currency", currency);
        return http.exchange("/payments", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    /** The default merchant creates a payment for itself. */
    ResponseEntity<Map> create(String key, String amount) {
        return create(TestAuth.DEFAULT_MERCHANT, key, PAYER, amount, "INR");
    }

    ResponseEntity<Map> post(String path) {
        return http.exchange(path, HttpMethod.POST, HttpEntity.EMPTY, Map.class);
    }

    long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    static final UUID PAYER = UUID.fromString("00000000-0000-0000-0000-000000000042");
}
