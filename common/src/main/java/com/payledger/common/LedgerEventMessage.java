package com.payledger.common;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by ledger-service (outbox) whenever a journal entry is posted; consumed by notification-service.
 * {@code eventId} is the outbox row id and the consumer's dedup key.
 */
public record LedgerEventMessage(UUID eventId, String type, UUID entryId, String entryType, String externalRef,
                                 UUID fromAccountId, UUID toAccountId, String fromOwner, String toOwner,
                                 long amountMinor, String currency, Instant occurredAt) {

    public static final String TOPIC = "ledger.events";
    public static final String DEAD_LETTER_TOPIC = TOPIC + ".DLT";
    public static final String ENTRY_POSTED = "ledger.entry.posted";
}
