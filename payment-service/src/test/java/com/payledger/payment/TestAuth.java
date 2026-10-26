package com.payledger.payment;

import com.payledger.security.testing.TestTokens;
import java.io.IOException;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * The identity test requests are sent as. Behaviour tests run as the MERCHANT "merchant-1" by default;
 * security tests switch identity or send their own Authorization header, which always wins. A plain static field
 * (not a ThreadLocal) because some tests call the API from executor threads.
 */
final class TestAuth {

    static final String DEFAULT_MERCHANT = "merchant-1";

    private static volatile String token;

    private TestAuth() {
    }

    static void asDefaultMerchant() {
        token = TestTokens.token(DEFAULT_MERCHANT, "MERCHANT");
    }

    static void as(String subject, String... roles) {
        token = TestTokens.token(subject, roles);
    }

    static void anonymous() {
        token = null;
    }

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
