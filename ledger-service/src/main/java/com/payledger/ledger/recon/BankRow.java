package com.payledger.ledger.recon;

import java.time.Instant;
import java.util.UUID;

/** One line of the bank's settlement file, tagged with the run and its physical line number (the restart key). */
public record BankRow(UUID runId, int lineNo, String externalRef, long amountMinor, String currency, Instant settledAt) {
}
