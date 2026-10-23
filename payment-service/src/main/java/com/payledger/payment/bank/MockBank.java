package com.payledger.payment.bank;

import com.payledger.payment.bank.BankDtos.AuthResponse;
import com.payledger.payment.bank.BankDtos.AuthorizeRequest;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stand-in for the bank (ADR 0011): in-memory, idempotent by reference, with configurable faults.
 * Fault bands are mutually exclusive slices of one dice roll: timeout, then lost response, then decline.
 */
@Component
@ConditionalOnProperty(name = "payledger.mock-bank.enabled", havingValue = "true", matchIfMissing = true)
public class MockBank {

    /** Thrown for the "timeout" fault: the bank did nothing and answers only after the client has given up. */
    public static class StalledException extends RuntimeException {
    }

    private final MockBankProperties props;
    private final Random random;
    private final Map<String, AuthResponse> store = new ConcurrentHashMap<>();
    private final AtomicLong authorizeCalls = new AtomicLong();

    public MockBank(MockBankProperties props) {
        this.props = props;
        this.random = props.seed() == 0 ? new Random() : new Random(props.seed());
    }

    public AuthResponse authorize(AuthorizeRequest request) throws InterruptedException {
        authorizeCalls.incrementAndGet();
        double roll;
        synchronized (random) {
            roll = random.nextDouble();
        }
        sleepLatency();
        AuthResponse known = store.get(request.reference());
        if (known != null) {
            return known; // idempotent by reference: same answer, no new fault
        }
        double timeoutBand = props.timeoutRate();
        double lostBand = timeoutBand + props.lostResponseRate();
        double declineBand = lostBand + props.declineRate();
        if (roll < timeoutBand) {
            Thread.sleep(props.stall().toMillis());
            throw new StalledException(); // never processed
        }
        if (roll < lostBand) {
            AuthResponse approved = record(request, "APPROVED", null);
            Thread.sleep(props.stall().toMillis()); // approved, but the answer arrives after the client left
            return approved;
        }
        if (roll < declineBand) {
            return record(request, "DECLINED", "insufficient funds");
        }
        return record(request, "APPROVED", null);
    }

    public Optional<AuthResponse> lookup(String reference) {
        return Optional.ofNullable(store.get(reference));
    }

    /** Capture/refund are reliable (faults apply to authorization only, ADR 0010). Empty = unknown or not approved. */
    public Optional<AuthResponse> settle(String reference, String newStatus) {
        return Optional.ofNullable(store.get(reference))
                .filter(a -> a.status().equals("APPROVED") || a.status().equals("CAPTURED"))
                .map(a -> new AuthResponse(a.reference(), a.bankRef(), newStatus, null))
                .map(a -> {
                    store.put(reference, a);
                    return a;
                });
    }

    /** Test hooks. */
    public long authorizeCalls() {
        return authorizeCalls.get();
    }

    public long approvedCount() {
        return store.values().stream().filter(a -> !a.status().equals("DECLINED")).count();
    }

    public long recordCount() {
        return store.size();
    }

    private AuthResponse record(AuthorizeRequest request, String status, String reason) {
        AuthResponse response = new AuthResponse(request.reference(), "BANK-" + UUID.randomUUID(), status, reason);
        AuthResponse previous = store.putIfAbsent(request.reference(), response);
        return previous != null ? previous : response;
    }

    private void sleepLatency() throws InterruptedException {
        int span = Math.max(0, props.latencyMaxMs() - props.latencyMinMs());
        int extra;
        synchronized (random) {
            extra = span == 0 ? 0 : random.nextInt(span + 1);
        }
        Thread.sleep(props.latencyMinMs() + extra);
    }
}
