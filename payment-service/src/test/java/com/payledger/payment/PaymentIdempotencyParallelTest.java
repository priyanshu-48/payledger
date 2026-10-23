package com.payledger.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/** SPEC section 7: the same request sent 50 times in parallel creates exactly one payment and one charge. */
@TestPropertySource(properties = {"payledger.mock-bank.latency-min-ms=150", "payledger.mock-bank.latency-max-ms=200"})
class PaymentIdempotencyParallelTest extends PaymentApiSupport {

    private static final int REQUESTS = 50;

    @Test
    void fiftyIdenticalRequestsCreateOnePaymentAndOneCharge() throws Exception {
        long callsBefore = bank.authorizeCalls();
        CountDownLatch startTogether = new CountDownLatch(1);
        List<Callable<ResponseEntity<Map>>> calls = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            calls.add(() -> {
                startTogether.await();
                return create("parallel-key", "99.00");
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        List<ResponseEntity<Map>> responses = new ArrayList<>();
        try {
            List<Future<ResponseEntity<Map>>> futures = new ArrayList<>();
            for (Callable<ResponseEntity<Map>> call : calls) {
                futures.add(pool.submit(call));
            }
            startTogether.countDown();
            for (Future<ResponseEntity<Map>> f : futures) {
                responses.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }

        long created = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count();
        assertThat(created).as("exactly one request created the payment").isEqualTo(1);
        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode())
                .isIn(HttpStatus.CREATED, HttpStatus.OK, HttpStatus.CONFLICT));
        Set<Object> ids = responses.stream().filter(r -> r.getStatusCode() != HttpStatus.CONFLICT)
                .map(r -> r.getBody().get("id")).collect(Collectors.toSet());
        assertThat(ids).hasSize(1);
        assertThat(count("payments")).as("one payment row").isEqualTo(1);
        assertThat(bank.authorizeCalls() - callsBefore).as("one call to the bank, i.e. one charge").isEqualTo(1);
        assertThat(count("payment_events")).isEqualTo(2); // created, authorized
    }
}
