package com.payledger.ledger.service;

import com.payledger.ledger.service.LedgerExceptions.TransferContentionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Deliberately NOT transactional: every retry must start a brand-new transaction. Conflicts are
 * OptimisticLockingFailureException (version mismatch) and PessimisticLockingFailureException
 * (Postgres deadlock / lock timeout); both mean "nothing was written, try again".
 */
@Service
public class OptimisticTransferExecutor {

    private final OptimisticLedgerService service;
    private final int maxAttempts;
    private final AtomicLong retries = new AtomicLong();

    public OptimisticTransferExecutor(OptimisticLedgerService service,
                                      @Value("${ledger.optimistic.max-attempts:20}") int maxAttempts) {
        this.service = service;
        this.maxAttempts = maxAttempts;
    }

    public TransferResult transfer(TransferCommand cmd) {
        for (int attempt = 1; ; attempt++) {
            try {
                return service.attempt(cmd);
            } catch (OptimisticLockingFailureException | PessimisticLockingFailureException e) {
                if (attempt >= maxAttempts) {
                    throw new TransferContentionException(attempt, e);
                }
                retries.incrementAndGet();
                backoff(attempt);
            }
        }
    }

    /** Total conflicts retried since startup (for benchmarks and, later, a metric). */
    public long retryCount() {
        return retries.get();
    }

    /** Random wait of 1..10*attempt ms so colliding transfers stop colliding in lockstep. */
    private static void backoff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, 10L * attempt + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransferContentionException(attempt, e);
        }
    }
}
