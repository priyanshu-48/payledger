package com.payledger.ledger.service;

import com.payledger.common.Direction;
import com.payledger.ledger.domain.JournalEntryRepository;
import com.payledger.ledger.domain.Posting;
import com.payledger.ledger.domain.PostingRepository;
import com.payledger.ledger.service.LedgerExceptions.IdempotencyConflictException;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Idempotency lookup shared by every locking strategy (ADR 0008): same key + same request = same answer. */
@Component
class IdempotencyReplays {

    private final JournalEntryRepository entries;
    private final PostingRepository postings;

    IdempotencyReplays(JournalEntryRepository entries, PostingRepository postings) {
        this.entries = entries;
        this.postings = postings;
    }

    /** Previous result for this key, if any; throws if the key was used for a different transfer. */
    Optional<TransferResult> find(TransferCommand cmd) {
        return entries.findByExternalRef(cmd.idempotencyKey()).map(entry -> {
            List<Posting> legs = postings.findByEntryId(entry.getId());
            Posting debit = legs.stream().filter(p -> p.getDirection() == Direction.DEBIT).findFirst().orElse(null);
            Posting credit = legs.stream().filter(p -> p.getDirection() == Direction.CREDIT).findFirst().orElse(null);
            boolean same = entry.getType() == cmd.type()
                    && legs.size() == 2 && debit != null && credit != null
                    && debit.getAccountId().equals(cmd.from())
                    && credit.getAccountId().equals(cmd.to())
                    && debit.getAmountMinor() == cmd.amount().minor()
                    && debit.getCurrency().equals(cmd.amount().currency());
            if (!same) {
                throw new IdempotencyConflictException(cmd.idempotencyKey());
            }
            return new TransferResult(entry.getId(), cmd.idempotencyKey(), cmd.from(), cmd.to(), cmd.amount(),
                    entry.getCreatedAt(), true);
        });
    }
}
