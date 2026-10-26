package com.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/** SPEC 6.8: Bucket4j limit on POST /transfers per client, answered with 429 and Retry-After. */
@TestPropertySource(properties = "payledger.ratelimit.transfers-per-minute=3")
class TransferRateLimitTest extends ApiTestSupport {

    private UUID wallet(String owner) {
        ResponseEntity<Map> r = http.postForEntity("/accounts", Map.of("ownerId", owner, "type", "USER_WALLET", "currency", "INR"), Map.class);
        return UUID.fromString((String) r.getBody().get("id"));
    }

    @Test
    void theFourthTransferInAMinuteIsRefusedAndOtherClientsAreNotAffected() {
        UUID settlement = createAccount("SETTLEMENT", "INR");
        UUID alice = wallet("alice");
        UUID bob = wallet("bob");
        transfer("fund-a", settlement, alice, "100.00", "INR"); // the ADMIN's own bucket: 2 of 3 used
        transfer("fund-b", settlement, bob, "100.00", "INR");

        TestAuth.as("alice", "USER");
        for (int i = 1; i <= 3; i++) {
            assertThat(transfer("a-" + i, alice, bob, "1.00", "INR").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        ResponseEntity<Map> limited = transfer("a-4", alice, bob, "1.00", "INR");

        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(Integer.parseInt(limited.getHeaders().getFirst("Retry-After"))).isBetween(1, 60);
        assertThat(limited.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");

        TestAuth.as("bob", "USER");
        assertThat(transfer("b-1", bob, alice, "1.00", "INR").getStatusCode()).as("bob has his own bucket").isEqualTo(HttpStatus.CREATED);
        assertThat(http.getForEntity("/accounts/{id}/balance", Map.class, bob).getStatusCode()).as("reads are not limited").isEqualTo(HttpStatus.OK);
        TestAuth.asAdmin();
        assertThat(balance(alice)).isEqualByComparingTo("98.00"); // 100 - 3 + 1: the refused 4th moved nothing
    }
}
