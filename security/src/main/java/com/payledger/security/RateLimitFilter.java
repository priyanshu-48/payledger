package com.payledger.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-client rate limits (SPEC 6.8, ADR 0019). The client is the token's subject, or the remote address when
 * the request has no valid token. Over the limit: 429 with Retry-After. Buckets live in memory, so limits are
 * per instance; the map is bounded so spoofed keys cannot exhaust memory.
 * Place it after the bearer-token filter so the subject is already known.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    /** {@code capacity} requests per {@code period} for {@code method} requests matching {@code pathPattern}. */
    public record Rule(String name, String method, String pathPattern, int capacity, Duration period) {
    }

    private static final int MAX_BUCKETS = 10_000;

    private final List<Rule> rules;
    private final TimeMeter timeMeter;
    private final AntPathMatcher matcher = new AntPathMatcher();
    private final Map<String, Bucket> buckets = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
            return size() > MAX_BUCKETS;
        }
    });

    public RateLimitFilter(List<Rule> rules) {
        this(rules, TimeMeter.SYSTEM_MILLISECONDS);
    }

    /** The time source is injectable so tests can move the clock instead of sleeping. */
    public RateLimitFilter(List<Rule> rules, TimeMeter timeMeter) {
        this.rules = List.copyOf(rules);
        this.timeMeter = timeMeter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Rule rule = matchingRule(request);
        if (rule == null) {
            chain.doFilter(request, response);
            return;
        }
        ConsumptionProbe probe = bucket(rule, clientKey(request)).tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            chain.doFilter(request, response);
            return;
        }
        long retryAfterSeconds = Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setHeader("X-RateLimit-Remaining", "0");
        ProblemResponses.write(response, HttpStatus.TOO_MANY_REQUESTS, "rate limit exceeded; retry later");
    }

    private Rule matchingRule(HttpServletRequest request) {
        for (Rule rule : rules) {
            if (rule.method().equalsIgnoreCase(request.getMethod())
                    && matcher.match(rule.pathPattern(), request.getRequestURI())) {
                return rule;
            }
        }
        return null;
    }

    private Bucket bucket(Rule rule, String clientKey) {
        return buckets.computeIfAbsent(rule.name() + "|" + clientKey, k -> Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(rule.capacity()).refillGreedy(rule.capacity(), rule.period()).build())
                .withCustomTimePrecision(timeMeter)
                .build());
    }

    private static String clientKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token && token.isAuthenticated()) {
            return "sub:" + token.getName();
        }
        return "ip:" + request.getRemoteAddr();
    }
}
