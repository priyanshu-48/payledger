package com.payledger.ledger;

import com.payledger.security.testing.TestTokens;
import java.io.IOException;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * The identity test requests are sent as. Behaviour tests run as an ADMIN by default so they keep testing
 * the ledger; security tests switch identity or send their own Authorization header, which always wins.
 * A plain static field (not a ThreadLocal) because some tests call the API from executor threads.
 */
final class TestAuth {

    private static volatile String token;

    private TestAuth() {
    }

    static void asAdmin() {
        token = TestTokens.token("test-admin", "ADMIN");
    }

    static void as(String subject, String... roles) {
        token = TestTokens.token(subject, roles);
    }

    static void anonymous() {
        token = null;
    }

    /** Adds the default identity to requests that do not carry their own Authorization header. */
    static void install(TestRestTemplate http) {
        boolean present = http.getRestTemplate().getInterceptors().stream().anyMatch(i -> i instanceof DefaultIdentity);
        if (!present) {
            http.getRestTemplate().getInterceptors().add(new DefaultIdentity());
        }
    }

    private static final class DefaultIdentity implements ClientHttpRequestInterceptor {
        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws IOException {
            String current = token;
            if (current != null && !request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
                request.getHeaders().setBearerAuth(current);
            }
            return execution.execute(request, body);
        }
    }
}
