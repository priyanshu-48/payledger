package com.payledger.ledger.service;

import com.payledger.ledger.domain.Account;
import com.payledger.ledger.domain.AccountBalance;
import com.payledger.ledger.domain.AccountBalanceRepository;
import com.payledger.ledger.domain.AccountRepository;
import com.payledger.ledger.service.LedgerExceptions.AccountNotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Optimistic strategy (ADR 0009): no row locks. Balances are read normally and written back with a
 * version check; if another transaction changed either row first, commit fails with an optimistic-lock
 * error and OptimisticTransferExecutor retries the whole attempt in a new transaction.
 */
@Service
public class OptimisticLedgerService {

    private final AccountRepository accounts;
    private final AccountBalanceRepository balances;
    private final IdempotencyReplays replays;
    private final TransferRecorder recorder;

    public OptimisticLedgerService(AccountRepository accounts, AccountBalanceRepository balances,
                                   IdempotencyReplays replays, TransferRecorder recorder) {
        this.accounts = accounts;
        this.balances = balances;
        this.replays = replays;
        this.recorder = recorder;
    }

    /** One attempt. May throw an optimistic-lock failure at commit; never call it in a retry loop from inside a transaction. */
    @Transactional
    public TransferResult attempt(TransferCommand cmd) {
        TransferRules.checkRequest(cmd);

        Optional<TransferResult> replay = replays.find(cmd);
        if (replay.isPresent()) {
            return replay.get();
        }

        AccountBalance fromBalance = read(cmd.from());
        AccountBalance toBalance = read(cmd.to());
        Account fromAccount = accounts.findById(cmd.from()).orElseThrow(() -> new AccountNotFoundException(cmd.from()));
        Account toAccount = accounts.findById(cmd.to()).orElseThrow(() -> new AccountNotFoundException(cmd.to()));
        // Funds are judged on the snapshot just read; a concurrent change makes the commit fail and retry.
        TransferRules.checkAccounts(cmd, fromAccount, toAccount, fromBalance);

        return recorder.record(cmd, fromBalance, toBalance);
    }

    private AccountBalance read(UUID accountId) {
        return balances.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }
}
