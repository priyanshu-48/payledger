package com.payledger.ledger;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import com.payledger.security.testing.TestJwtConfiguration;
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

/** HTTP-level helpers shared by the API tests. Each test starts from empty ledger tables. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestJwtConfiguration.class)
abstract class ApiTestSupport extends AbstractPostgresTest {

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanLedger() {
        TestAuth.install(http);
        TestAuth.asAdmin();
        jdbc.execute("TRUNCATE postings, journal_entries, account_balances, accounts, outbox CASCADE");
    }

    UUID createAccount(String type, String currency) {
        ResponseEntity<Map> r = http.postForEntity("/accounts",
                Map.of("ownerId", "owner-" + UUID.randomUUID(), "type", type, "currency", currency), Map.class);
        return UUID.fromString((String) r.getBody().get("id"));
    }

    ResponseEntity<Map> transfer(String key, Object from, Object to, String amount, String currency) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        Map<String, Object> body = Map.of("fromAccountId", from.toString(), "toAccountId", to.toString(),
                "amount", new BigDecimal(amount), "currency", currency);
        return http.exchange("/transfers", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    BigDecimal balance(UUID account) {
        Map body = http.getForObject("/accounts/{id}/balance", Map.class, account);
        return new BigDecimal(body.get("balance").toString());
    }

    /** Money enters the system as a transfer from a SETTLEMENT account (ADR 0003). */
    UUID fundedWallet(UUID settlement, String amount) {
        UUID wallet = createAccount("USER_WALLET", "INR");
        transfer("fund-" + wallet, settlement, wallet, amount, "INR");
        return wallet;
    }

    long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}
