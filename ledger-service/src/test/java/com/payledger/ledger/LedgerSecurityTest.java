package com.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

/** SPEC 6.8: authentication, roles, and ownership of accounts, over real HTTP. */
class LedgerSecurityTest extends ApiTestSupport {

    /** Creates an account with a chosen owner (as the default ADMIN identity). */
    private UUID account(String owner, String type) {
        ResponseEntity<Map> r = http.postForEntity("/accounts", Map.of("ownerId", owner, "type", type, "currency", "INR"), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("id"));
    }

    private HttpStatus balanceStatus(UUID account) {
        return (HttpStatus) http.getForEntity("/accounts/{id}/balance", Map.class, account).getStatusCode();
    }

    private HttpStatus post(String path, Object body) {
        return (HttpStatus) http.postForEntity(path, body, Map.class).getStatusCode();
    }

    private ResponseEntity<Map> payment(String key, UUID from, UUID to, String amount) {
        return transfer(key, from, to, amount, "INR");
    }

    @Test
    void everythingExceptHealthNeedsAToken() {
        UUID someAccount = UUID.randomUUID();
        TestAuth.anonymous();

        assertThat(balanceStatus(someAccount)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post("/accounts", Map.of("ownerId", "a", "type", "USER_WALLET", "currency", "INR")))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(payment("k", someAccount, UUID.randomUUID(), "1.00").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.getForEntity("/recon/runs/{id}", Map.class, someAccount).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post("/admin/dlt/replay", Map.of())).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.getForEntity("/actuator/health", Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/not-a-real-path", Map.class).getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void expiredForgedAndMalformedTokensAreRejected() {
        UUID account = UUID.randomUUID();
        for (String bad : List.of(TestTokens.expired("alice", "USER"), TestTokens.signedByAnotherKey("alice", "ADMIN"),
                TestTokens.withIssuer("somebody-else", "alice", "ADMIN"), "not.a.jwt", "garbage")) {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(bad);
            ResponseEntity<Map> r = http.exchange("/accounts/{id}/balance", HttpMethod.GET, new HttpEntity<>(headers), Map.class, account);
            assertThat(r.getStatusCode()).as("token %s", bad.length() > 20 ? "(a signed one)" : bad).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(r.getHeaders().getFirst("WWW-Authenticate")).isEqualTo("Bearer");
        }
    }

    @Test
    void operatorPathsAreForAdminsOnly() {
        UUID wallet = account("alice", "USER_WALLET");
        for (String[] identity : new String[][]{{"alice", "USER"}, {"shop", "MERCHANT"}}) {
            TestAuth.as(identity[0], identity[1]);
            assertThat(http.getForEntity("/recon/runs/{id}", Map.class, UUID.randomUUID()).getStatusCode())
                    .as("recon as %s", identity[1]).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(post("/admin/dlt/replay", Map.of())).as("replay as %s", identity[1]).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(post("/accounts/" + wallet + "/freeze", Map.of())).as("freeze as %s", identity[1]).isEqualTo(HttpStatus.FORBIDDEN);
        }
        TestAuth.asAdmin();
        assertThat(post("/accounts/" + wallet + "/freeze", Map.of())).isEqualTo(HttpStatus.OK);
    }

    @Test
    void userACannotReadUserBsWalletButCanReadTheirOwn() {
        UUID alice = account("alice", "USER_WALLET");
        UUID bob = account("bob", "USER_WALLET");

        TestAuth.as("alice", "USER");
        assertThat(balanceStatus(alice)).isEqualTo(HttpStatus.OK);
        assertThat(balanceStatus(bob)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(balanceStatus(UUID.randomUUID())).as("an unknown id looks the same as someone else's").isEqualTo(HttpStatus.FORBIDDEN);

        TestAuth.as("bob", "USER");
        assertThat(balanceStatus(bob)).isEqualTo(HttpStatus.OK);
        assertThat(balanceStatus(alice)).isEqualTo(HttpStatus.FORBIDDEN);

        TestAuth.asAdmin();
        assertThat(balanceStatus(alice)).isEqualTo(HttpStatus.OK);
        assertThat(balanceStatus(UUID.randomUUID())).as("an ADMIN is told it does not exist").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aUserCanPayIntoSomeoneElsesWalletButNeverDebitIt() {
        UUID settlement = account("platform", "SETTLEMENT");
        UUID alice = account("alice", "USER_WALLET");
        UUID bob = account("bob", "USER_WALLET");
        transfer("fund-a", settlement, alice, "100.00", "INR");
        transfer("fund-b", settlement, bob, "100.00", "INR");

        TestAuth.as("alice", "USER");
        assertThat(payment("a-to-b", alice, bob, "10.00").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(payment("a-steals", bob, alice, "50.00").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(payment("a-from-settlement", settlement, alice, "50.00").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        TestAuth.asAdmin();
        assertThat(balance(alice)).isEqualByComparingTo(new BigDecimal("90.00"));
        assertThat(balance(bob)).isEqualByComparingTo(new BigDecimal("110.00"));
    }

    @Test
    void peopleMayOnlyCreateTheirOwnKindOfAccountForThemselves() {
        Map<String, String> ownWallet = Map.of("ownerId", "alice", "type", "USER_WALLET", "currency", "INR");
        TestAuth.as("alice", "USER");
        assertThat(post("/accounts", ownWallet)).isEqualTo(HttpStatus.CREATED);
        assertThat(post("/accounts", Map.of("ownerId", "bob", "type", "USER_WALLET", "currency", "INR"))).as("for someone else").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/accounts", Map.of("ownerId", "alice", "type", "MERCHANT", "currency", "INR"))).as("wrong kind").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/accounts", Map.of("ownerId", "alice", "type", "SETTLEMENT", "currency", "INR"))).as("system account").isEqualTo(HttpStatus.FORBIDDEN);

        TestAuth.as("shop", "MERCHANT");
        assertThat(post("/accounts", Map.of("ownerId", "shop", "type", "MERCHANT", "currency", "INR"))).isEqualTo(HttpStatus.CREATED);
        assertThat(post("/accounts", Map.of("ownerId", "shop", "type", "USER_WALLET", "currency", "INR"))).isEqualTo(HttpStatus.FORBIDDEN);

        TestAuth.as("nobody", new String[0]);
        assertThat(post("/accounts", Map.of("ownerId", "nobody", "type", "USER_WALLET", "currency", "INR"))).as("no role").isEqualTo(HttpStatus.FORBIDDEN);

        TestAuth.asAdmin();
        assertThat(post("/accounts", Map.of("ownerId", "platform", "type", "SETTLEMENT", "currency", "INR"))).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void freezingBlocksTransfersAndEveryPrivilegedActionIsAudited() {
        UUID settlement = account("platform", "SETTLEMENT");
        UUID bob = account("bob", "USER_WALLET");
        jdbc.execute("TRUNCATE audit_log"); // forget the account.created rows above; this test is about freeze

        assertThat(post("/accounts/" + bob + "/freeze", Map.of())).isEqualTo(HttpStatus.OK);
        assertThat(transfer("into-frozen", settlement, bob, "5.00", "INR").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(post("/accounts/" + bob + "/unfreeze", Map.of())).isEqualTo(HttpStatus.OK);
        assertThat(transfer("into-active", settlement, bob, "5.00", "INR").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        List<Map<String, Object>> audit = jdbc.queryForList("SELECT actor, action, target FROM audit_log ORDER BY id");
        assertThat(audit).extracting(r -> r.get("action")).containsExactly("account.frozen", "account.unfrozen");
        assertThat(audit).allSatisfy(r -> {
            assertThat(r.get("actor")).isEqualTo("test-admin");
            assertThat(r.get("target")).isEqualTo(bob.toString());
        });
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET actor = 'someone-else'"))
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log")).rootCause().hasMessageContaining("append-only");
    }

    @Test
    void anAdminCreatingAnAccountIsAudited() {
        jdbc.execute("TRUNCATE audit_log");
        UUID id = account("platform", "SETTLEMENT");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'account.created' AND target = ?",
                Long.class, id.toString())).isEqualTo(1);
    }

    @Test
    void requestsMustBeJsonApiCalls() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        assertThat(http.exchange("/accounts", HttpMethod.POST, new HttpEntity<>("hello", headers), Map.class)
                .getStatusCode().is4xxClientError()).isTrue();
    }
}
