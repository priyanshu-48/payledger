package com.payledger.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;

/** RFC 9457 problem JSON for failures raised in servlet filters, where controller advice cannot reach. */
final class ProblemResponses {

    private ProblemResponses() {
    }

    /** {@code detail} must be a fixed message: never put request data or exception text in it. */
    static void write(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase()
                + "\",\"status\":" + status.value() + ",\"detail\":\"" + detail + "\"}");
    }
}
