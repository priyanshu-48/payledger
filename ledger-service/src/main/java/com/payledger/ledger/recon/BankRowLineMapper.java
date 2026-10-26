package com.payledger.ledger.recon;

import java.time.Instant;
import java.util.UUID;
import org.springframework.batch.item.file.LineMapper;

/**
 * Parses {@code external_ref,amount_minor,currency,settled_at}. A bad line throws, which fails the step: a
 * damaged bank file is reported, never silently skipped (ADR 0017). The line number counts physical lines
 * (the header is line 1), so it identifies a row uniquely within a file.
 */
public class BankRowLineMapper implements LineMapper<BankRow> {

    private final UUID runId;

    public BankRowLineMapper(UUID runId) {
        this.runId = runId;
    }

    @Override
    public BankRow mapLine(String line, int lineNumber) {
        String[] fields = line.split(",", -1);
        if (fields.length != 4) {
            throw new IllegalArgumentException("expected 4 columns but found " + fields.length + ": " + line);
        }
        String externalRef = fields[0].trim();
        String currency = fields[2].trim();
        if (externalRef.isEmpty()) {
            throw new IllegalArgumentException("empty external_ref");
        }
        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("bad currency '" + currency + "'");
        }
        long amountMinor = Long.parseLong(fields[1].trim()); // NumberFormatException is an IllegalArgumentException
        String settled = fields[3].trim();
        return new BankRow(runId, lineNumber, externalRef, amountMinor, currency,
                settled.isEmpty() ? null : Instant.parse(settled));
    }
}
