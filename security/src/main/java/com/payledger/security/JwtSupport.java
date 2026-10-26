package com.payledger.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** One place that decides what a valid token is: correct signature (set by the caller), not expired, right issuer. */
public final class JwtSupport {

    public static final String ISSUER = "payledger-auth";

    private JwtSupport() {
    }

    public static NimbusJwtDecoder requireIssuer(NimbusJwtDecoder decoder, String issuer) {
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer)));
        return decoder;
    }
}
