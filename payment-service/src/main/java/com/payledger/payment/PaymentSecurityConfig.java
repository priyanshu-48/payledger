package com.payledger.payment;

import jakarta.servlet.DispatcherType;
import com.payledger.payment.bank.MockBankKeyFilter;
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
 * URL-level rules (ADR 0019): payments need a valid token; the mock bank is closed to everyone except our own
 * BankClient (shared key, checked by MockBankKeyFilter); health and metrics are open. Which payment a merchant
 * may touch is decided in the service layer (PaymentAccess).
 */
@Configuration
@EnableWebSecurity
@Import(SecurityConfiguration.class)
class PaymentSecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiSecurity api,
                                            @Value("${payledger.ratelimit.payments-per-minute}") int paymentsPerMinute,
                                            @Value("${payledger.bank.api-key:}") String bankApiKey) throws Exception {
        RateLimitFilter limits = new RateLimitFilter(List.of(new RateLimitFilter.Rule(
                "payments", HttpMethod.POST.name(), "/payments", paymentsPerMinute, Duration.ofMinutes(1))));
        return api.apply(http)
                .authorizeHttpRequests(a -> a
                        // framework errors (415, 404, ...) are forwarded to /error; keep their real status
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/mock-bank/**").permitAll() // guarded by MockBankKeyFilter instead of a token
                        .requestMatchers("/payments/**").authenticated()
                        .anyRequest().denyAll())
                .addFilterBefore(new MockBankKeyFilter(bankApiKey), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(limits, BearerTokenAuthenticationFilter.class)
                .build();
    }
}
