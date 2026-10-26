package com.payledger.payment.bank;

import com.payledger.payment.bank.BankDtos.AuthResponse;
import com.payledger.payment.bank.BankDtos.AuthorizeRequest;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Synchronous calls to the bank with a hard timeout. Anything that is not a clear answer (timeout, 5xx,
 * connection error) is reported as UNKNOWN: we never assume what the bank did.
 */
@Component
public class BankClient {

    public enum Result { APPROVED, DECLINED, NOT_FOUND, UNKNOWN }

    public record Outcome(Result result, String bankRef, String reason) {
    }

    /** Capture or refund could not be confirmed by the bank; nothing was changed on our side. */
    public static class BankUnavailableException extends RuntimeException {
        public BankUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final RestClient rest;
    private final Environment environment;

    public BankClient(Environment environment, @Value("${payledger.bank.timeout}") Duration timeout,
                      @Value("${payledger.bank.api-key:}") String apiKey) {
        this.environment = environment;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.rest = RestClient.builder().requestFactory(factory).defaultHeader(MockBankKeyFilter.HEADER, apiKey).build();
    }

    public Outcome authorize(UUID reference, long amountMinor, String currency) {
        try {
            AuthResponse r = rest.post().uri(url("/authorizations"))
                    .body(new AuthorizeRequest(reference.toString(), amountMinor, currency))
                    .retrieve().body(AuthResponse.class);
            return toOutcome(r);
        } catch (RestClientException e) {
            return new Outcome(Result.UNKNOWN, null, e.getClass().getSimpleName());
        }
    }

    /** The status check: what does the bank say about this reference? */
    public Outcome lookup(UUID reference) {
        try {
            AuthResponse r = rest.get().uri(url("/authorizations/" + reference)).retrieve().body(AuthResponse.class);
            return toOutcome(r);
        } catch (HttpClientErrorException.NotFound e) {
            return new Outcome(Result.NOT_FOUND, null, "bank has no record");
        } catch (RestClientException e) {
            return new Outcome(Result.UNKNOWN, null, e.getClass().getSimpleName());
        }
    }

    public void capture(UUID reference) {
        post("/authorizations/" + reference + "/capture");
    }

    public void refund(UUID reference) {
        post("/authorizations/" + reference + "/refund");
    }

    private void post(String path) {
        try {
            rest.post().uri(url(path)).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new BankUnavailableException("bank did not confirm " + path, e);
        }
    }

    private Outcome toOutcome(AuthResponse r) {
        if (r == null) {
            return new Outcome(Result.UNKNOWN, null, "empty response");
        }
        return switch (r.status()) {
            case "APPROVED", "CAPTURED", "REFUNDED" -> new Outcome(Result.APPROVED, r.bankRef(), null);
            case "DECLINED" -> new Outcome(Result.DECLINED, null, r.reason());
            default -> new Outcome(Result.UNKNOWN, null, "unexpected status " + r.status());
        };
    }

    /** Read per call: tests point this at a random server port that exists only after startup. */
    private String url(String path) {
        return environment.getProperty("payledger.bank.base-url") + path;
    }
}
