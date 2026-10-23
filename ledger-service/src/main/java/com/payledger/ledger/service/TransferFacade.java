package com.payledger.ledger.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Deliberately NOT transactional. If two requests with the same Idempotency-Key race on different
 * account pairs, the loser fails on the external_ref unique constraint and its transaction rolls back
 * completely. Here, in a fresh transaction, we read the winner's result (or report the key conflict).
 */
@Service
public class TransferFacade {

    private final LedgerService ledger;

    public TransferFacade(LedgerService ledger) {
        this.ledger = ledger;
    }

    public TransferResult transfer(TransferCommand cmd) {
        try {
            return ledger.transfer(cmd);
        } catch (DataIntegrityViolationException e) {
            return ledger.findReplay(cmd).orElseThrow(() -> e);
        }
    }
}
