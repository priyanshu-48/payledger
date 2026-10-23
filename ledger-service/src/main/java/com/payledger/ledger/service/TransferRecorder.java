package com.payledger.ledger.service;

import com.payledger.common.Direction;
import com.payledger.ledger.domain.AccountBalance;
import com.payledger.ledger.domain.JournalEntry;
import com.payledger.ledger.domain.JournalEntryRepository;
import com.payledger.ledger.domain.Posting;
import com.payledger.ledger.domain.PostingRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The write half of a transfer; runs inside the caller's transaction. */
@Component
class TransferRecorder {

    private final JournalEntryRepository entries;
    private final PostingRepository postings;

    TransferRecorder(JournalEntryRepository entries, PostingRepository postings) {
        this.entries = entries;
        this.postings = postings;
    }

    TransferResult record(TransferCommand cmd, AccountBalance fromBalance, AccountBalance toBalance) {
        // Parent row first: postings reference it by foreign key.
        JournalEntry entry = entries.save(new JournalEntry(UUID.randomUUID(), cmd.idempotencyKey(), cmd.type()));
        long minor = cmd.amount().minor();
        String currency = cmd.amount().currency();
        postings.save(new Posting(entry.getId(), cmd.from(), Direction.DEBIT, minor, currency));
        postings.save(new Posting(entry.getId(), cmd.to(), Direction.CREDIT, minor, currency));
        fromBalance.apply(Direction.DEBIT, minor);
        toBalance.apply(Direction.CREDIT, minor);
        // Balances are managed entities: dirty checking writes the UPDATEs at commit, in this transaction.
        // The deferred DB trigger re-checks debits == credits at that same commit (ADR 0005).
        return new TransferResult(entry.getId(), cmd.idempotencyKey(), cmd.from(), cmd.to(), cmd.amount(),
                entry.getCreatedAt(), false);
    }
}
