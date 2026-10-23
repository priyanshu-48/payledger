package com.payledger.ledger.service;

import com.payledger.common.CurrencyMismatchException;
import com.payledger.common.InsufficientFundsException;
import com.payledger.ledger.domain.Account;
import com.payledger.ledger.domain.AccountBalance;
import com.payledger.ledger.service.LedgerExceptions.AccountNotActiveException;
import com.payledger.ledger.service.LedgerExceptions.InvalidTransferException;

/** Business checks every locking strategy applies identically. */
final class TransferRules {

    private TransferRules() {
    }

    static void checkRequest(TransferCommand cmd) {
        if (!cmd.amount().isPositive()) {
            throw new InvalidTransferException("amount must be positive");
        }
        if (cmd.from().equals(cmd.to())) {
            throw new InvalidTransferException("from and to must differ");
        }
    }

    /** Call once the payer's balance cannot change under us (row locked, or version-checked at commit). */
    static void checkAccounts(TransferCommand cmd, Account from, Account to, AccountBalance fromBalance) {
        if (!from.isActive()) {
            throw new AccountNotActiveException(from.getId());
        }
        if (!to.isActive()) {
            throw new AccountNotActiveException(to.getId());
        }
        String currency = cmd.amount().currency();
        if (!from.getCurrency().equals(currency)) {
            throw new CurrencyMismatchException(from.getCurrency(), currency);
        }
        if (!to.getCurrency().equals(currency)) {
            throw new CurrencyMismatchException(to.getCurrency(), currency);
        }
        if (fromBalance.isFloorZero() && fromBalance.getBalanceMinor() < cmd.amount().minor()) {
            throw new InsufficientFundsException(cmd.from());
        }
    }
}
