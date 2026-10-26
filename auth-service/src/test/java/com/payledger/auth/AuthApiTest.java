package com.payledger.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payledger.security.JwtSupport;
import com.payledger.security.testing.LogCapture;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "auth.bootstrap-admin-password=bootstrap-admin-pw-123")
class AuthApiTest extends AbstractPostgresTest {

    private static final String ADMIN_PASSWORD = "bootstrap-admin-pw-123";

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    private ResponseEntity<Map> login(String username, String password) {
        return http.postForEntity("/auth/token", Map.of("username", username, "password", password), Map.class);
    }

    private String token(String username, String password) {
        return (String) login(username, password).getBody().get("accessToken");
    }

    private ResponseEntity<Map> createUser(String bearer, Map<String, String> body) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return http.exchange("/auth/users", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    @Test
    void anAdminLogsInAndTheTokenVerifiesAgainstThePublishedKey() {
        ResponseEntity<Map> response = login("admin", ADMIN_PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("tokenType", "Bearer").containsEntry("expiresIn", 900);

        // what ledger-service and payment-service do: fetch the key set over HTTP and check the token
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri("http://localhost:" + port + "/.well-known/jwks.json").build();
        JwtSupport.requireIssuer(decoder, JwtSupport.ISSUER);
        Jwt jwt = decoder.decode((String) response.getBody().get("accessToken"));
        assertThat(jwt.getSubject()).isEqualTo("admin");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ADMIN");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("payledger-auth");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void theKeySetPublishesOnlyThePublicKey() {
        String jwks = http.getForObject("/.well-known/jwks.json", String.class);

        assertThat(jwks).contains("\"kty\":\"RSA\"").contains("\"n\":").contains("\"e\":");
        for (String privateField : List.of("\"d\":", "\"p\":", "\"q\":", "\"dp\":", "\"dq\":", "\"qi\":")) {
            assertThat(jwks).doesNotContain(privateField);
        }
    }

    @Test
    void wrongPasswordAndUnknownUserAreIndistinguishable() {
        ResponseEntity<Map> wrongPassword = login("admin", "definitely-wrong-password");
        ResponseEntity<Map> unknownUser = login("nobody-here", "definitely-wrong-password");

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownUser.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownUser.getBody()).isEqualTo(wrongPassword.getBody());
    }

    @Test
    void onlyAnAdminCreatesUsersAndInputIsValidated() {
        String admin = token("admin", ADMIN_PASSWORD);
        Map<String, String> alice = Map.of("username", "alice", "password", "alice-secret-pw-1", "role", "USER");

        assertThat(createUser(null, alice).getStatusCode()).as("no token").isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<Map> created = createUser(admin, alice);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("username", "alice").containsEntry("role", "USER");
        assertThat(created.getBody()).doesNotContainKey("password");

        String aliceToken = token("alice", "alice-secret-pw-1");
        assertThat(createUser(aliceToken, Map.of("username", "mallory", "password", "mallory-secret-1", "role", "ADMIN"))
                .getStatusCode()).as("a USER cannot create an ADMIN").isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(createUser(admin, alice).getStatusCode()).as("duplicate").isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<Map> weak = createUser(admin, Map.of("username", "bob", "password", "short", "role", "USER"));
        assertThat(weak.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(weak.getBody().toString()).as("the password is never echoed").doesNotContain("short");
        assertThat(createUser(admin, Map.of("username", "bob", "password", "bob-secret-pw-1", "role", "ROOT"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(createUser(admin, Map.of("username", "Bad Name!", "password", "bob-secret-pw-1", "role", "USER"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void passwordsAreStoredAsBcryptHashes() {
        createUser(token("admin", ADMIN_PASSWORD),
                Map.of("username", "carol", "password", "carol-secret-pw-1", "role", "MERCHANT"));

        String stored = jdbc.queryForObject("SELECT password_hash FROM users WHERE username = 'carol'", String.class);

        assertThat(stored).startsWith("$2").doesNotContain("carol-secret-pw-1");
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE username = 'carol'", String.class)).isEqualTo("MERCHANT");
    }

    @Test
    void userCreationIsAuditedAndTheAuditLogCannotBeChanged() {
        createUser(token("admin", ADMIN_PASSWORD),
                Map.of("username", "dave", "password", "dave-secret-pw-1", "role", "USER"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT actor, action, target, details->>'role' AS role FROM audit_log WHERE target = 'dave'");

        assertThat(row).containsEntry("actor", "admin").containsEntry("action", "user.created")
                .containsEntry("role", "USER");
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET actor = 'someone'"))
                .rootCause().hasMessageContaining("audit_log is append-only: UPDATE not allowed");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log"))
                .rootCause().hasMessageContaining("audit_log is append-only: DELETE not allowed");
    }

    @Test
    void requestAndResponseObjectsMaskTheirSecretsIfSomeoneLogsThem() {
        // Spring prints decoded request and response objects when DEBUG is switched on for the MVC layer
        assertThat(new AuthController.LoginRequest("admin", "plain-text-password").toString())
                .doesNotContain("plain-text-password").contains("admin");
        assertThat(new AuthController.CreateUserRequest("erin", "plain-text-password", "USER").toString())
                .doesNotContain("plain-text-password");
        assertThat(new AuthController.TokenResponse("eyJsecret.token.value", "Bearer", 900).toString())
                .doesNotContain("eyJsecret");
    }

    @Test
    void logsNeverContainPasswordsOrTokens() {
        try (LogCapture logs = new LogCapture()) {
            String admin = token("admin", ADMIN_PASSWORD);
            login("admin", "a-wrong-password-eve-tried");
            createUser(admin, Map.of("username", "erin", "password", "erin-secret-pw-1", "role", "USER"));
            createUser(admin, Map.of("username", "erin", "password", "erin-secret-pw-1", "role", "USER"));
            createUser(admin, Map.of("username", "x", "password", "erin-weak", "role", "USER"));
            String everything = logs.everything();

            assertThat(logs.size()).as("something was logged, so the check is meaningful").isPositive();
            assertThat(everything).doesNotContain(ADMIN_PASSWORD).doesNotContain("a-wrong-password-eve-tried")
                    .doesNotContain("erin-secret-pw-1").doesNotContain("erin-weak")
                    .doesNotContain(admin).doesNotContain("Bearer ").doesNotContain("eyJ");
        }
    }
}
