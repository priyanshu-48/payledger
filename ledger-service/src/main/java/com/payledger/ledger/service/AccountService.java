package com.payledger.ledger.service;

import com.payledger.common.Money;
import com.payledger.ledger.domain.Account;
import com.payledger.ledger.domain.AccountStatus;
import com.payledger.ledger.domain.AccountType;
import com.payledger.security.AuditLog;
import com.payledger.security.Caller;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account operations on behalf of a caller: checks ownership, does the work through {@link LedgerService},
 * and writes the audit row for privileged actions in the same transaction.
 */
@Service
public class AccountService {

    private final LedgerService ledger;
    private final LedgerAccess access;
    private final AuditLog audit;

    public AccountService(LedgerService ledger, LedgerAccess access, AuditLog audit) {
        this.ledger = ledger;
        this.access = access;
        this.audit = audit;
    }

    @Transactional
    public Account create(Caller caller, String ownerId, AccountType type, String currency) {
        access.requireMayCreate(caller, ownerId, type);
        Account account = ledger.createAccount(ownerId, type, currency);
        if (caller.isAdmin()) {
            audit.record(caller.subject(), "account.created", account.getId().toString(),
                    Map.of("owner", ownerId, "type", type.name(), "currency", currency));
        }
        return account;
    }

    @Transactional(readOnly = true)
    public Money balance(Caller caller, UUID accountId) {
        access.requireMayRead(caller, accountId);
        return ledger.getBalance(accountId);
    }

    /** ADMIN only: freeze or reactivate an account. */
    @Transactional
    public Account setStatus(Caller caller, UUID accountId, AccountStatus status) {
        access.requireAdmin(caller);
        Account account = ledger.setStatus(accountId, status);
        audit.record(caller.subject(), status == AccountStatus.FROZEN ? "account.frozen" : "account.unfrozen",
                accountId.toString(), Map.of("owner", account.getOwnerId()));
        return account;
    }
}
