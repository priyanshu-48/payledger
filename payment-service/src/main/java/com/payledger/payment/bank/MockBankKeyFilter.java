package com.payledger.payment.bank;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The stand-in bank is not part of the user-facing API (ADR 0011, 0019): only our own BankClient may call it,
 * and it proves it with a shared key from the environment. Anything else gets 401. An empty configured key
 * means "no key accepted", never "no key needed".
 */
public class MockBankKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Bank-Api-Key";

    private final byte[] expected;

    public MockBankKeyFilter(String apiKey) {
        this.expected = apiKey == null ? new byte[0] : apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/mock-bank/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        boolean ok = expected.length > 0 && presented != null
                && MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8)); // constant time
        if (ok) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
                + "\"detail\":\"a valid bank api key is required\"}");
    }
}
