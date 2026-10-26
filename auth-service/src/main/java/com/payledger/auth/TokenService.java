package com.payledger.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Issues short-lived RS256 access tokens: {@code sub} = username, {@code roles} = [ROLE] (ADR 0018). */
@Service
class TokenService {

    record Issued(String accessToken, long expiresInSeconds) {
    }

    private final RSAKey key;
    private final AuthProperties properties;
    private final RSASSASigner signer;

    TokenService(RSAKey key, AuthProperties properties) throws JOSEException {
        this.key = key;
        this.properties = properties;
        this.signer = new RSASSASigner(key);
    }

    Issued issue(String username, String role) {
        Instant now = Instant.now();
        Instant expires = now.plus(properties.tokenTtl());
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.issuer()).subject(username).claim("roles", List.of(role))
                .issueTime(Date.from(now)).expirationTime(Date.from(expires)).jwtID(UUID.randomUUID().toString())
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(signer);
            return new Issued(jwt.serialize(), properties.tokenTtl().toSeconds());
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot sign token", e);
        }
    }
}
