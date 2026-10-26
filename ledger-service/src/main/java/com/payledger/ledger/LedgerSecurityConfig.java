package com.payledger.ledger;

import jakarta.servlet.DispatcherType;
import com.payledger.security.ApiSecurity;
import com.payledger.security.RateLimitFilter;
import com.payledger.security.SecurityConfiguration;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * URL-level rules (ADR 0019): everything needs a valid token except the health and metrics endpoints, and the
 * operator paths need ADMIN. Which account a USER or MERCHANT may touch is decided in the service layer
 * (LedgerAccess), not here.
 */
@Configuration
@EnableWebSecurity
@Import(SecurityConfiguration.class)
class LedgerSecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiSecurity api,
                                            @Value("${payledger.ratelimit.transfers-per-minute}") int transfersPerMinute)
            throws Exception {
        RateLimitFilter limits = new RateLimitFilter(List.of(new RateLimitFilter.Rule(
                "transfers", HttpMethod.POST.name(), "/transfers", transfersPerMinute, Duration.ofMinutes(1))));
        return api.apply(http)
                .authorizeHttpRequests(a -> a
                        // framework errors (415, 404, ...) are forwarded to /error; keep their real status
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/admin/**", "/recon/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/accounts/*/freeze", "/accounts/*/unfreeze").hasRole("ADMIN")
                        .requestMatchers("/accounts/**", "/transfers").authenticated()
                        .anyRequest().denyAll())
                .addFilterAfter(limits, BearerTokenAuthenticationFilter.class)
                .build();
    }
}
