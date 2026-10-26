package com.payledger.ledger.service;

import com.payledger.common.Money;
import com.payledger.ledger.domain.Account;
import com.payledger.ledger.domain.AccountStatus;
import com.payledger.ledger.domain.AccountType;
import com.payledger.security.AuditLog;
import com.payledger.security.Caller;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbc;

    public AccountService(LedgerService ledger, LedgerAccess access, AuditLog audit, JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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

    /** One row of the admin list: the account with its current balance. */
    public record Listed(UUID id, String ownerId, AccountType type, String currency, String status, Money balance,
                         Instant createdAt) {
    }

    /** ADMIN only: newest first, optionally filtered by owner (exact) and type. */
    @Transactional(readOnly = true)
    public List<Listed> list(Caller caller, String ownerId, AccountType type, int page, int size) {
        access.requireAdmin(caller);
        return jdbc.query("""
                SELECT a.id, a.owner_id, a.type, a.currency, a.status, b.balance_minor, a.created_at
                FROM accounts a JOIN account_balances b ON b.account_id = a.id
                WHERE (?::text IS NULL OR a.owner_id = ?) AND (?::text IS NULL OR a.type = ?)
                ORDER BY a.created_at DESC, a.id LIMIT ? OFFSET ?
                """,
                (rs, i) -> new Listed(rs.getObject("id", UUID.class), rs.getString("owner_id"),
                        AccountType.valueOf(rs.getString("type")), rs.getString("currency"), rs.getString("status"),
                        Money.of(rs.getLong("balance_minor"), rs.getString("currency")),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                ownerId, ownerId, type == null ? null : type.name(), type == null ? null : type.name(),
                size, (long) page * size);
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
