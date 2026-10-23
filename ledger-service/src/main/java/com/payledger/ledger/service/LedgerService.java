package com.payledger.ledger.service;

import com.payledger.common.CurrencyMismatchException;
import com.payledger.common.Direction;
import com.payledger.common.InsufficientFundsException;
import com.payledger.common.Money;
import com.payledger.ledger.domain.Account;
import com.payledger.ledger.domain.AccountBalance;
import com.payledger.ledger.domain.AccountBalanceRepository;
import com.payledger.ledger.domain.AccountRepository;
import com.payledger.ledger.domain.AccountType;
import com.payledger.ledger.domain.EntryType;
import com.payledger.ledger.domain.JournalEntry;
import com.payledger.ledger.domain.JournalEntryRepository;
import com.payledger.ledger.domain.Posting;
import com.payledger.ledger.domain.PostingRepository;
import com.payledger.ledger.service.LedgerExceptions.AccountNotActiveException;
import com.payledger.ledger.service.LedgerExceptions.AccountNotFoundException;
import com.payledger.ledger.service.LedgerExceptions.IdempotencyConflictException;
import com.payledger.ledger.service.LedgerExceptions.InvalidTransferException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Callers must go through the Spring proxy: a call from another method of this class would skip the
 * transaction (self-invocation). TransferFacade is the entry point for transfers.
 */
@Service
public class LedgerService {

    private final AccountRepository accounts;
    private final AccountBalanceRepository balances;
    private final JournalEntryRepository entries;
    private final PostingRepository postings;

    public LedgerService(AccountRepository accounts, AccountBalanceRepository balances,
                         JournalEntryRepository entries, PostingRepository postings) {
        this.accounts = accounts;
        this.balances = balances;
        this.entries = entries;
        this.postings = postings;
    }

    @Transactional
    public Account createAccount(String ownerId, AccountType type, String currency) {
        Account account = accounts.save(new Account(UUID.randomUUID(), ownerId, type, currency));
        balances.save(new AccountBalance(account.getId(), type == AccountType.USER_WALLET));
        return account;
    }

    @Transactional(readOnly = true)
    public Money getBalance(UUID accountId) {
        Account account = accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
        AccountBalance balance = balances.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
        return Money.of(balance.getBalanceMinor(), account.getCurrency());
    }

    /**
     * One atomic double-entry posting (SPEC 6.1): DEBIT {@code from}, CREDIT {@code to}.
     * Money safety: both balance rows are locked with SELECT ... FOR UPDATE in sorted account-id order
     * (so two opposite transfers cannot deadlock) before anything is read or written.
     */
    @Transactional
    public TransferResult transfer(TransferCommand cmd) {
        if (!cmd.amount().isPositive()) {
            throw new InvalidTransferException("amount must be positive");
        }
        if (cmd.from().equals(cmd.to())) {
            throw new InvalidTransferException("from and to must differ");
        }

        // Fast path for retries: no locks needed to answer a request we already processed.
        Optional<TransferResult> replay = replayOf(cmd);
        if (replay.isPresent()) {
            return replay.get();
        }

        // Lock in a fixed global order (ADR 0007). UUID.compareTo is a stable total order.
        boolean fromFirst = cmd.from().compareTo(cmd.to()) < 0;
        AccountBalance firstLocked = lock(fromFirst ? cmd.from() : cmd.to());
        AccountBalance secondLocked = lock(fromFirst ? cmd.to() : cmd.from());
        AccountBalance fromBalance = fromFirst ? firstLocked : secondLocked;
        AccountBalance toBalance = fromFirst ? secondLocked : firstLocked;

        // A concurrent request with the same key may have committed while we waited for the locks.
        replay = replayOf(cmd);
        if (replay.isPresent()) {
            return replay.get();
        }

        Account fromAccount = accounts.findById(cmd.from()).orElseThrow(() -> new AccountNotFoundException(cmd.from()));
        Account toAccount = accounts.findById(cmd.to()).orElseThrow(() -> new AccountNotFoundException(cmd.to()));
        if (!fromAccount.isActive()) {
            throw new AccountNotActiveException(fromAccount.getId());
        }
        if (!toAccount.isActive()) {
            throw new AccountNotActiveException(toAccount.getId());
        }
        String currency = cmd.amount().currency();
        if (!fromAccount.getCurrency().equals(currency)) {
            throw new CurrencyMismatchException(fromAccount.getCurrency(), currency);
        }
        if (!toAccount.getCurrency().equals(currency)) {
            throw new CurrencyMismatchException(toAccount.getCurrency(), currency);
        }
        if (fromBalance.isFloorZero() && fromBalance.getBalanceMinor() < cmd.amount().minor()) {
            throw new InsufficientFundsException(cmd.from());
        }

        // Parent row first: postings reference it by foreign key.
        JournalEntry entry = entries.save(new JournalEntry(UUID.randomUUID(), cmd.idempotencyKey(), EntryType.TRANSFER));
        long minor = cmd.amount().minor();
        postings.save(new Posting(entry.getId(), cmd.from(), Direction.DEBIT, minor, currency));
        postings.save(new Posting(entry.getId(), cmd.to(), Direction.CREDIT, minor, currency));
        fromBalance.apply(Direction.DEBIT, minor);
        toBalance.apply(Direction.CREDIT, minor);
        // Balances are managed entities: dirty checking writes the UPDATEs at commit, in this transaction.
        // The deferred DB trigger re-checks debits == credits at that same commit (ADR 0005).

        return new TransferResult(entry.getId(), cmd.idempotencyKey(), cmd.from(), cmd.to(), cmd.amount(),
                entry.getCreatedAt(), false);
    }

    /** Previous result for this key, if any; throws if the key was used for a different transfer. */
    @Transactional(readOnly = true)
    public Optional<TransferResult> findReplay(TransferCommand cmd) {
        return replayOf(cmd);
    }

    private Optional<TransferResult> replayOf(TransferCommand cmd) {
        return entries.findByExternalRef(cmd.idempotencyKey()).map(entry -> {
            List<Posting> legs = postings.findByEntryId(entry.getId());
            Posting debit = legs.stream().filter(p -> p.getDirection() == Direction.DEBIT).findFirst().orElse(null);
            Posting credit = legs.stream().filter(p -> p.getDirection() == Direction.CREDIT).findFirst().orElse(null);
            boolean same = entry.getType() == EntryType.TRANSFER
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

    private AccountBalance lock(UUID accountId) {
        return balances.findForUpdate(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }
}
