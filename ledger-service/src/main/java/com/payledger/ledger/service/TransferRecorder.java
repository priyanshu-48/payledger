package com.payledger.ledger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payledger.common.Direction;
import com.payledger.common.LedgerEventMessage;
import com.payledger.ledger.domain.Account;
import com.payledger.ledger.domain.AccountBalance;
import com.payledger.ledger.domain.JournalEntry;
import com.payledger.ledger.domain.JournalEntryRepository;
import com.payledger.ledger.domain.Posting;
import com.payledger.ledger.domain.PostingRepository;
import com.payledger.outbox.OutboxWriter;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The write half of a transfer; runs inside the caller's transaction. */
@Component
class TransferRecorder {

    private final JournalEntryRepository entries;
    private final PostingRepository postings;
    private final OutboxWriter outbox;
    private final ObjectMapper json;

    TransferRecorder(JournalEntryRepository entries, PostingRepository postings, OutboxWriter outbox,
                     ObjectMapper json) {
        this.entries = entries;
        this.postings = postings;
        this.outbox = outbox;
        this.json = json;
    }

    TransferResult record(TransferCommand cmd, Account fromAccount, Account toAccount,
                          AccountBalance fromBalance, AccountBalance toBalance) {
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
        publish(entry, cmd, fromAccount, toAccount);
        return new TransferResult(entry.getId(), cmd.idempotencyKey(), cmd.from(), cmd.to(), cmd.amount(),
                entry.getCreatedAt(), false);
    }

    /** Outbox row in the same transaction as the entry: published if and only if the entry committed (ADR 0015). */
    private void publish(JournalEntry entry, TransferCommand cmd, Account from, Account to) {
        UUID eventId = UUID.randomUUID();
        LedgerEventMessage message = new LedgerEventMessage(eventId, LedgerEventMessage.ENTRY_POSTED, entry.getId(),
                cmd.type().name(), cmd.idempotencyKey(), cmd.from(), cmd.to(), from.getOwnerId(), to.getOwnerId(),
                cmd.amount().minor(), cmd.amount().currency(), Instant.now());
        try {
            outbox.write(eventId, entry.getId(), message.type(), json.writeValueAsString(message));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise " + message.type(), e);
        }
    }
}
