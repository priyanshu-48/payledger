package com.payledger.ledger.service;

import com.payledger.common.Money;
import java.util.UUID;

/** Move {@code amount} from one account to another. The idempotency key becomes journal_entries.external_ref. */
public record TransferCommand(String idempotencyKey, UUID from, UUID to, Money amount) {
}
