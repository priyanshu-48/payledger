package com.payledger.auth;

import com.nimbusds.jose.jwk.RSAKey;
import jakarta.servlet.DispatcherType;
import com.payledger.security.ApiSecurity;
import com.payledger.security.RateLimitFilter;
import com.payledger.security.JwtSupport;
import com.payledger.security.SecurityConfiguration;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
@Import(SecurityConfiguration.class)
class AuthConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthConfig.class);

    /** auth-service verifies its own tokens with its own key (it is the only service that has it). */
    @Bean
    @Primary
    JwtDecoder localJwtDecoder(RSAKey signingKey, AuthProperties properties) throws Exception {
        return JwtSupport.requireIssuer(NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey()).build(),
                properties.issuer());
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiSecurity api, AuthProperties properties) throws Exception {
        RateLimitFilter loginLimit = new RateLimitFilter(List.of(new RateLimitFilter.Rule(
                "login", HttpMethod.POST.name(), "/auth/token", properties.rateLimit().loginPerMinute(), Duration.ofMinutes(1))));
        return api.apply(http)
                .authorizeHttpRequests(a -> a
                        // framework errors (415, 404, ...) are forwarded to /error; keep their real status
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/auth/token", "/.well-known/jwks.json").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/auth/users").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .addFilterAfter(loginLimit, BearerTokenAuthenticationFilter.class)
                .build();
    }

    /** Creates user "admin" once, from AUTH_ADMIN_PASSWORD, when there are no users yet. */
    @Bean
    ApplicationRunner bootstrapAdmin(UserService users, AuthProperties properties) {
        return args -> {
            String password = properties.bootstrapAdminPassword();
            if (users.count() > 0) {
                return;
            }
            if (password == null || password.isBlank()) {
                log.warn("no users exist and AUTH_ADMIN_PASSWORD is not set: nobody can log in");
                return;
            }
            users.create("bootstrap", "admin", password, "ADMIN");
            log.info("bootstrap admin user created");
        };
    }
}
