package com.payledger.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code jwtPrivateKey} is a PKCS8 PEM; blank means "generate one at startup". {@code bootstrapAdminPassword}
 * creates the first ADMIN when no user exists. Both come from environment variables, never from code.
 */
@ConfigurationProperties("auth")
public record AuthProperties(String issuer, Duration tokenTtl, String jwtPrivateKey, String bootstrapAdminPassword,
                             RateLimit rateLimit) {

    public record RateLimit(int loginPerMinute) {
    }
}
