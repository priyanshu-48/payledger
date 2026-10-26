package com.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** The read-only lists the admin UI needs (ADR 0021): ADMIN only, newest first, size capped. */
class ListEndpointsTest extends ReconTestBase {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> ROWS = new ParameterizedTypeReference<>() { };

    private ResponseEntity<List<Map<String, Object>>> get(String url) {
        return http.exchange(url, HttpMethod.GET, null, ROWS);
    }

    private HttpStatus status(String url) {
        return (HttpStatus) http.getForEntity(url, String.class).getStatusCode();
    }

    @Test
    void adminListsAccountsWithBalancesAndCanFilterByOwnerAndType() {
        UUID settlement = createAccount("SETTLEMENT", "INR");
        UUID wallet = fundedWallet(settlement, "12.50");

        List<Map<String, Object>> all = get("/accounts").getBody();
        assertThat(all).extracting(a -> a.get("id")).contains(settlement.toString(), wallet.toString());
        Map<String, Object> row = all.stream().filter(a -> a.get("id").equals(wallet.toString())).findFirst().orElseThrow();
        assertThat(row.get("balance").toString()).isEqualTo("12.5");
        assertThat(row).containsEntry("type", "USER_WALLET").containsEntry("status", "ACTIVE").containsKey("createdAt");

        assertThat(get("/accounts?type=SETTLEMENT").getBody()).extracting(a -> a.get("type")).containsOnly("SETTLEMENT");
        String owner = (String) row.get("ownerId");
        assertThat(get("/accounts?ownerId=" + owner).getBody()).extracting(a -> a.get("id")).containsExactly(wallet.toString());
        assertThat(get("/accounts?ownerId=nobody").getBody()).isEmpty();
    }

    @Test
    void pageSizeIsCappedAndPagesDoNotOverlap() {
        for (int i = 0; i < 3; i++) {
            createAccount("USER_WALLET", "INR");
        }

        List<Map<String, Object>> first = get("/accounts?size=2&page=0").getBody();
        List<Map<String, Object>> second = get("/accounts?size=2&page=1").getBody();

        assertThat(first).hasSize(2);
        assertThat(second).hasSizeGreaterThanOrEqualTo(1);
        assertThat(second).extracting(a -> a.get("id")).doesNotContainAnyElementsOf(first.stream().map(a -> a.get("id")).toList());
        assertThat(get("/accounts?size=100000").getBody()).hasSizeLessThanOrEqualTo(200);
    }

    @Test
    void accountListIsAdminOnly() {
        createAccount("USER_WALLET", "INR");
        for (String role : List.of("USER", "MERCHANT")) {
            TestAuth.as("someone", role);
            assertThat(status("/accounts")).as(role).isEqualTo(HttpStatus.FORBIDDEN);
        }
        TestAuth.anonymous();
        assertThat(status("/accounts")).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void adminListsReconRunsNewestFirstAndOthersAreRefused() throws Exception {
        captures(2, 10_000);
        java.time.Instant start = java.time.Instant.now().minusSeconds(3600);
        java.time.Instant end = java.time.Instant.now().plusSeconds(3600);
        upload(csv(List.of(row("payment-capture:ghost-a", 5, "INR"))), start, end);
        Thread.sleep(20);
        upload(csv(List.of(row("payment-capture:ghost-b", 6, "INR"))), start, end);

        List<Map<String, Object>> runs = get("/recon/runs").getBody();

        assertThat(runs).hasSize(2);
        assertThat(runs.get(0).get("startedAt").toString()).isGreaterThan(runs.get(1).get("startedAt").toString());
        assertThat(runs.get(0)).containsEntry("status", "COMPLETED").containsKeys("missingInLedger", "missingInBank");

        TestAuth.as("merchant-1", "MERCHANT");
        assertThat(status("/recon/runs")).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
