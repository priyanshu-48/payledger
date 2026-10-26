package com.payledger.security.testing;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.payledger.security.JwtSupport;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Mints tokens for tests with a throwaway RSA key generated in memory when the JVM starts (no key file exists
 * anywhere). {@link #decoder()} accepts exactly those tokens, with the same expiry and issuer rules as production.
 */
public final class TestTokens {

    private static final KeyPair KEYS = generate();
    private static final KeyPair OTHER_KEYS = generate();

    private TestTokens() {
    }

    public static String token(String subject, String... roles) {
        return sign(KEYS, JwtSupport.ISSUER, subject, Instant.now().plus(Duration.ofMinutes(15)), roles);
    }

    public static String expired(String subject, String... roles) {
        return sign(KEYS, JwtSupport.ISSUER, subject, Instant.now().minus(Duration.ofMinutes(5)), roles);
    }

    public static String withIssuer(String issuer, String subject, String... roles) {
        return sign(KEYS, issuer, subject, Instant.now().plus(Duration.ofMinutes(15)), roles);
    }

    /** Looks valid but was signed with a key the decoder does not trust. */
    public static String signedByAnotherKey(String subject, String... roles) {
        return sign(OTHER_KEYS, JwtSupport.ISSUER, subject, Instant.now().plus(Duration.ofMinutes(15)), roles);
    }

    public static JwtDecoder decoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
        return JwtSupport.requireIssuer(decoder, JwtSupport.ISSUER);
    }

    private static String sign(KeyPair keys, String issuer, String subject, Instant expiresAt, String[] roles) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer).subject(subject).claim("roles", List.of(roles))
                    .issueTime(Date.from(Instant.now().minusSeconds(1))).expirationTime(Date.from(expiresAt))
                    .jwtID(UUID.randomUUID().toString()).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot sign test token", e);
        }
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
