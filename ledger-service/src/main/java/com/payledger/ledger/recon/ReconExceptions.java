package com.payledger.ledger.recon;

import java.util.UUID;

/** Reconciliation failures; the web layer maps each to an HTTP status. */
public final class ReconExceptions {

    private ReconExceptions() {
    }

    public static class ReconRunNotFoundException extends RuntimeException {
        public ReconRunNotFoundException(UUID id) {
            super("reconciliation run not found: " + id);
        }
    }

    /** The same file and period is being reconciled right now (409). */
    public static class ReconRunInProgressException extends RuntimeException {
        public ReconRunInProgressException(UUID id) {
            super("reconciliation run " + id + " for this file and period is still running");
        }
    }

    public static class InvalidReconRequestException extends RuntimeException {
        public InvalidReconRequestException(String message) {
            super(message);
        }
    }
}
