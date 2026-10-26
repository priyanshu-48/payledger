package com.payledger.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payledger.security.testing.TestTokens;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CallerAndJwtTest {

    private final JwtDecoder decoder = TestTokens.decoder();
    private final JwtAuthenticationConverter converter = new SecurityConfiguration().jwtAuthenticationConverter();

    @Test
    void aValidTokenBecomesACallerWithItsSubjectAndRoles() {
        Jwt jwt = decoder.decode(TestTokens.token("alice", "USER", "MERCHANT"));

        Caller caller = Caller.from(converter.convert(jwt));

        assertThat(caller.subject()).isEqualTo("alice");
        assertThat(caller.roles()).containsExactlyInAnyOrder("USER", "MERCHANT");
        assertThat(caller.isAdmin()).isFalse();
        assertThat(caller.hasRole("USER")).isTrue();
    }

    @Test
    void adminIsRecognisedAndSystemIsAnAdmin() {
        assertThat(Caller.of("root", "ADMIN").isAdmin()).isTrue();
        assertThat(Caller.system().isAdmin()).isTrue();
    }

    @Test
    void noAuthenticationIsAccessDenied() {
        assertThatThrownBy(() -> Caller.from(null)).isInstanceOf(AccessDeniedException.class);
        JwtAuthenticationToken anonymousLike = new JwtAuthenticationToken(
                Jwt.withTokenValue("t").header("alg", "none").subject("x").build(), List.of());
        assertThat(Caller.from(anonymousLike).roles()).isEmpty();
    }

    @Test
    void expiredWrongIssuerForeignKeyAndTamperedTokensAreRejected() {
        assertThatThrownBy(() -> decoder.decode(TestTokens.expired("alice", "USER"))).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(TestTokens.withIssuer("someone-else", "alice", "USER")))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(TestTokens.signedByAnotherKey("alice", "ADMIN")))
                .isInstanceOf(JwtException.class);
        String valid = TestTokens.token("alice", "USER");
        String[] parts = valid.split("\\.");
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(java.util.Base64.getUrlDecoder().decode(parts[1])).replace("USER", "ADMIN").getBytes());
        assertThatThrownBy(() -> decoder.decode(parts[0] + "." + forgedPayload + "." + parts[2]))
                .isInstanceOf(JwtException.class);
    }
}
