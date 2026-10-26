package com.payledger.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Import from a service ({@code @Import(SecurityConfiguration.class)}) and write its own SecurityFilterChain
 * with {@link ApiSecurity#apply}. Properties: {@code payledger.security.jwks-uri} and {@code payledger.security.issuer}.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    /** Verifies tokens against the auth-service's published key. The key set is fetched lazily on first use. */
    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder jwtDecoder(@Value("${payledger.security.jwks-uri}") String jwksUri,
                          @Value("${payledger.security.issuer:" + JwtSupport.ISSUER + "}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        return JwtSupport.requireIssuer(decoder, issuer);
    }

    /** The token's {@code roles} claim ["ADMIN"] becomes the authority ROLE_ADMIN. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    ApiSecurity apiSecurity(JwtAuthenticationConverter converter) {
        return new ApiSecurity(converter);
    }

    @Bean
    AuditLog auditLog(JdbcTemplate jdbc, ObjectMapper json) {
        return new AuditLog(jdbc, json);
    }
}
