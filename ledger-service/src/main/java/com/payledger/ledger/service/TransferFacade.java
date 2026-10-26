package com.payledger.ledger.service;

import com.payledger.security.Caller;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Deliberately NOT transactional. If two requests with the same Idempotency-Key race, the loser fails on
 * the external_ref unique constraint and its transaction rolls back completely. Here, in a fresh
 * transaction, we read the winner's result (or report the key conflict). Also picks the locking strategy,
 * and checks that the caller may debit the payer account before anything is read or locked.
 * Every call is timed as {@code ledger.transfer.duration{strategy,outcome}} (ADR 0020).
 */
@Service
public class TransferFacade {

    private final LedgerService pessimistic;
    private final OptimisticTransferExecutor optimistic;
    private final LockingStrategy strategy;
    private final LedgerAccess access;
    private final MeterRegistry metrics;

    public TransferFacade(LedgerService pessimistic, OptimisticTransferExecutor optimistic, LedgerAccess access,
                          @Value("${ledger.locking-strategy:PESSIMISTIC}") LockingStrategy strategy,
                          MeterRegistry metrics) {
        this.pessimistic = pessimistic;
        this.optimistic = optimistic;
        this.access = access;
        this.strategy = strategy;
        this.metrics = metrics;
    }

    public TransferResult transfer(Caller caller, TransferCommand cmd) {
        Timer.Sample sample = Timer.start(metrics);
        String outcome = "created";
        try {
            access.requireMayDebit(caller, cmd.from());
            TransferResult result;
            try {
                result = strategy == LockingStrategy.OPTIMISTIC ? optimistic.transfer(cmd) : pessimistic.transfer(cmd);
            } catch (DataIntegrityViolationException e) {
                result = pessimistic.findReplay(cmd).orElseThrow(() -> e);
            }
            outcome = result.replayed() ? "replayed" : "created";
            return result;
        } catch (RuntimeException e) {
            outcome = e.getClass().getSimpleName();   // refusals are a small fixed set of exception types
            throw e;
        } finally {
            sample.stop(Timer.builder("ledger.transfer.duration")
                    .description("Time to process a transfer request")
                    .tag("strategy", strategy.name()).tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .serviceLevelObjectives(Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50),
                            Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1))
                    .register(metrics));
        }
    }
}
