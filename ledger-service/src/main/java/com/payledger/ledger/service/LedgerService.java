package com.payledger.ledger.service;

import com.payledger.common.Money;
import com.payledger.ledger.domain.Account;
import com.payledger.ledger.domain.AccountBalance;
import com.payledger.ledger.domain.AccountBalanceRepository;
import com.payledger.ledger.domain.AccountRepository;
import com.payledger.ledger.domain.AccountStatus;
import com.payledger.ledger.domain.AccountType;
import com.payledger.ledger.service.LedgerExceptions.AccountNotFoundException;
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
    private final IdempotencyReplays replays;
    private final TransferRecorder recorder;

    public LedgerService(AccountRepository accounts, AccountBalanceRepository balances,
                         IdempotencyReplays replays, TransferRecorder recorder) {
        this.accounts = accounts;
        this.balances = balances;
        this.replays = replays;
        this.recorder = recorder;
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
     * Pessimistic strategy, one atomic double-entry posting (SPEC 6.1): DEBIT {@code from}, CREDIT {@code to}.
     * Money safety: both balance rows are locked with SELECT ... FOR UPDATE in sorted account-id order
     * (so two opposite transfers cannot deadlock) before anything is read or written.
     */
    @Transactional
    public TransferResult transfer(TransferCommand cmd) {
        TransferRules.checkRequest(cmd);

        // Fast path for retries: no locks needed to answer a request we already processed.
        Optional<TransferResult> replay = replays.find(cmd);
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
        replay = replays.find(cmd);
        if (replay.isPresent()) {
            return replay.get();
        }

        Account fromAccount = accounts.findById(cmd.from()).orElseThrow(() -> new AccountNotFoundException(cmd.from()));
        Account toAccount = accounts.findById(cmd.to()).orElseThrow(() -> new AccountNotFoundException(cmd.to()));
        TransferRules.checkAccounts(cmd, fromAccount, toAccount, fromBalance);

        return recorder.record(cmd, fromAccount, toAccount, fromBalance, toBalance);
    }

    /**
     * Freezes or reactivates an account. The balance row is locked first, the same lock transfers take, so a
     * freeze waits for in-flight transfers on this account and later transfers see the new status.
     */
    @Transactional
    public Account setStatus(UUID accountId, AccountStatus status) {
        lock(accountId);
        Account account = accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
        account.changeStatus(status);
        return account;
    }

    /** Previous result for this key, if any; throws if the key was used for a different transfer. */
    @Transactional(readOnly = true)
    public Optional<TransferResult> findReplay(TransferCommand cmd) {
        return replays.find(cmd);
    }

    private AccountBalance lock(UUID accountId) {
        return balances.findForUpdate(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }
}
