package com.payledger.security.testing;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Import in a service's tests so its resource server trusts {@link TestTokens} instead of fetching a real key set. */
@TestConfiguration(proxyBeanMethods = false)
public class TestJwtConfiguration {

    @Bean
    @Primary
    JwtDecoder testJwtDecoder() {
        return TestTokens.decoder();
    }
}
