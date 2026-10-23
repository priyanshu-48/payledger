package com.payledger.ledger.service;

import com.payledger.common.Money;
import com.payledger.ledger.domain.EntryType;
import java.util.UUID;

/**
 * Move {@code amount} from one account to another. The idempotency key becomes journal_entries.external_ref
 * and {@code type} is recorded on the journal entry (TRANSFER for wallet transfers, PAYMENT_CAPTURE / REFUND
 * for payment events).
 */
public record TransferCommand(String idempotencyKey, UUID from, UUID to, Money amount, EntryType type) {

    public TransferCommand(String idempotencyKey, UUID from, UUID to, Money amount) {
        this(idempotencyKey, from, to, amount, EntryType.TRANSFER);
    }
}
