package com.payledger.ledger.service;

import com.payledger.ledger.domain.Account;
import com.payledger.ledger.domain.AccountRepository;
import com.payledger.ledger.domain.AccountType;
import com.payledger.security.Caller;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Who may touch which account (ADR 0019). This is the service-layer ownership check: it does not depend on
 * how the request arrived, only on the {@link Caller}. ADMIN may do anything. A USER or MERCHANT may read an
 * account or debit from it only if they own it (account.owner_id equals the token's subject), and may create
 * only an account of their own kind for themselves. Crediting somebody else's wallet is allowed (peer to peer).
 * A caller who is not an ADMIN gets the same refusal for a missing account as for someone else's, so ids
 * cannot be probed.
 */
@Component
public class LedgerAccess {

    private static final String DENIED = "you are not allowed to do this";

    private final AccountRepository accounts;

    public LedgerAccess(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public void requireMayRead(Caller caller, UUID accountId) {
        requireOwnerOrAdmin(caller, accountId);
    }

    public void requireMayDebit(Caller caller, UUID accountId) {
        requireOwnerOrAdmin(caller, accountId);
    }

    public void requireMayCreate(Caller caller, String ownerId, AccountType type) {
        if (caller.isAdmin()) {
            return;
        }
        boolean ownKind = (caller.hasRole(Caller.USER) && type == AccountType.USER_WALLET)
                || (caller.hasRole(Caller.MERCHANT) && type == AccountType.MERCHANT);
        if (!ownKind || !caller.subject().equals(ownerId)) {
            throw new AccessDeniedException(DENIED);
        }
    }

    public void requireAdmin(Caller caller) {
        if (!caller.isAdmin()) {
            throw new AccessDeniedException(DENIED);
        }
    }

    private void requireOwnerOrAdmin(Caller caller, UUID accountId) {
        if (caller.isAdmin()) {
            return; // a missing account is then reported as 404 by the operation itself
        }
        if (!caller.hasRole(Caller.USER) && !caller.hasRole(Caller.MERCHANT)) {
            throw new AccessDeniedException(DENIED);
        }
        Account account = accounts.findById(accountId).orElseThrow(() -> new AccessDeniedException(DENIED));
        if (!account.getOwnerId().equals(caller.subject())) {
            throw new AccessDeniedException(DENIED);
        }
    }
}
