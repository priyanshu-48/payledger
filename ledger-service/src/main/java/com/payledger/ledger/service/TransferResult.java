package com.payledger.ledger.service;

import com.payledger.common.Money;
import java.time.Instant;
import java.util.UUID;

public record TransferResult(UUID entryId, String idempotencyKey, UUID from, UUID to, Money amount,
                             Instant createdAt, boolean replayed) {
}
