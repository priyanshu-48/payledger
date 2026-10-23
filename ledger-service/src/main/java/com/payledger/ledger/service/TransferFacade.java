package com.payledger.ledger.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Deliberately NOT transactional. If two requests with the same Idempotency-Key race, the loser fails on
 * the external_ref unique constraint and its transaction rolls back completely. Here, in a fresh
 * transaction, we read the winner's result (or report the key conflict). Also picks the locking strategy.
 */
@Service
public class TransferFacade {

    private final LedgerService pessimistic;
    private final OptimisticTransferExecutor optimistic;
    private final LockingStrategy strategy;

    public TransferFacade(LedgerService pessimistic, OptimisticTransferExecutor optimistic,
                          @Value("${ledger.locking-strategy:PESSIMISTIC}") LockingStrategy strategy) {
        this.pessimistic = pessimistic;
        this.optimistic = optimistic;
        this.strategy = strategy;
    }

    public TransferResult transfer(TransferCommand cmd) {
        try {
            return strategy == LockingStrategy.OPTIMISTIC ? optimistic.transfer(cmd) : pessimistic.transfer(cmd);
        } catch (DataIntegrityViolationException e) {
            return pessimistic.findReplay(cmd).orElseThrow(() -> e);
        }
    }
}
