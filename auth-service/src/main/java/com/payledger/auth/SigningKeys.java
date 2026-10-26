package com.payledger.auth;

import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The token signing key (ADR 0018): from the environment if provided, otherwise generated for this process. */
@Configuration(proxyBeanMethods = false)
class SigningKeys {

    private static final Logger log = LoggerFactory.getLogger(SigningKeys.class);

    @Bean
    RSAKey signingKey(AuthProperties properties) throws Exception {
        String pem = properties.jwtPrivateKey();
        KeyPair pair = pem == null || pem.isBlank() ? generate() : fromPem(pem);
        return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyUse(KeyUse.SIGNATURE)
                .keyID(UUID.randomUUID().toString())
                .build();
    }

    private static KeyPair generate() throws Exception {
        log.warn("AUTH_JWT_PRIVATE_KEY is not set: generated a signing key for this process; "
                + "all tokens become invalid when auth-service restarts");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static KeyPair fromPem(String pem) throws Exception {
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        KeyFactory factory = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) factory.generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
        return new KeyPair(publicKey, privateKey);
    }
}
