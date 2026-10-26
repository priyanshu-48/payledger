package com.payledger.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.payledger.security.RateLimitFilter.Rule;
import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class RateLimitFilterTest {

    /** A clock the test moves by hand. */
    static class FakeClock implements TimeMeter {
        final AtomicLong nanos = new AtomicLong();

        @Override
        public long currentTimeNanos() {
            return nanos.get();
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }

        void advance(Duration d) {
            nanos.addAndGet(d.toNanos());
        }
    }

    private final FakeClock clock = new FakeClock();
    private final RateLimitFilter filter = new RateLimitFilter(
            List.of(new Rule("transfers", "POST", "/transfers", 3, Duration.ofMinutes(1))), clock);

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String subject) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(subject).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    private MockHttpServletResponse call(String method, String path, String remoteAddr) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        request.setRemoteAddr(remoteAddr);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void allowsTheBurstThenAnswers429WithRetryAfter() throws Exception {
        authenticateAs("alice");
        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse ok = call("POST", "/transfers", "1.1.1.1");
            assertThat(ok.getStatus()).isEqualTo(200);
            assertThat(ok.getHeader("X-RateLimit-Remaining")).isEqualTo(String.valueOf(2 - i));
        }

        MockHttpServletResponse limited = call("POST", "/transfers", "1.1.1.1");

        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(Integer.parseInt(limited.getHeader("Retry-After"))).isBetween(1, 60);
        assertThat(limited.getContentType()).startsWith("application/problem+json");
        assertThat(limited.getContentAsString()).contains("\"status\":429");
    }

    @Test
    void theLimitIsPerSubjectAndRefillsWithTime() throws Exception {
        authenticateAs("alice");
        for (int i = 0; i < 3; i++) {
            call("POST", "/transfers", "1.1.1.1");
        }
        assertThat(call("POST", "/transfers", "1.1.1.1").getStatus()).isEqualTo(429);

        authenticateAs("bob"); // another client is not affected
        assertThat(call("POST", "/transfers", "1.1.1.1").getStatus()).isEqualTo(200);

        authenticateAs("alice");
        clock.advance(Duration.ofSeconds(21)); // one token refills every 20 s (3 per minute)
        assertThat(call("POST", "/transfers", "1.1.1.1").getStatus()).isEqualTo(200);
        assertThat(call("POST", "/transfers", "1.1.1.1").getStatus()).isEqualTo(429);
    }

    @Test
    void withoutATokenTheClientIsTheRemoteAddress() throws Exception {
        for (int i = 0; i < 3; i++) {
            call("POST", "/transfers", "10.0.0.1");
        }
        assertThat(call("POST", "/transfers", "10.0.0.1").getStatus()).isEqualTo(429);
        assertThat(call("POST", "/transfers", "10.0.0.2").getStatus()).isEqualTo(200);
    }

    @Test
    void onlyMatchingMethodAndPathAreLimited() throws Exception {
        authenticateAs("alice");
        for (int i = 0; i < 10; i++) {
            assertThat(call("GET", "/transfers", "1.1.1.1").getStatus()).isEqualTo(200);
            assertThat(call("POST", "/accounts", "1.1.1.1").getStatus()).isEqualTo(200);
        }
    }
}
