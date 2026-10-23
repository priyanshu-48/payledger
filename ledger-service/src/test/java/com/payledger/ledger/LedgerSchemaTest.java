package com.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Proves the database enforces the ledger rules on its own, whatever the application does. */
@SpringBootTest
class LedgerSchemaTest extends AbstractPostgresTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;

    TransactionTemplate tx;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE postings, journal_entries, account_balances, accounts CASCADE");
        tx = new TransactionTemplate(txm);
    }

    UUID account(String type, String currency, long balance) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, owner_id, type, currency, status) VALUES (?, 'o', ?, ?, 'ACTIVE')",
                id, type, currency);
        jdbc.update("INSERT INTO account_balances (account_id, balance_minor, floor_zero) VALUES (?, ?, ?)",
                id, balance, type.equals("USER_WALLET"));
        return id;
    }

    UUID entry(String ref) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO journal_entries (id, external_ref, type) VALUES (?, ?, 'TRANSFER')", id, ref);
        return id;
    }

    void posting(UUID entry, UUID account, String direction, long amount, String currency) {
        jdbc.update("INSERT INTO postings (id, entry_id, account_id, direction, amount_minor, currency) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(), entry, account, direction, amount, currency);
    }

    long postingCount() {
        return jdbc.queryForObject("SELECT count(*) FROM postings", Long.class);
    }

    @Test
    void balancedEntryCommits() {
        UUID a = account("SETTLEMENT", "INR", 0), b = account("USER_WALLET", "INR", 0);
        tx.executeWithoutResult(s -> {
            UUID e = entry("ok-1");
            posting(e, a, "DEBIT", 500, "INR");
            posting(e, b, "CREDIT", 500, "INR");
        });
        assertThat(postingCount()).isEqualTo(2);
    }

    @Test
    void unbalancedEntryIsRejectedAtCommit() {
        UUID a = account("SETTLEMENT", "INR", 0), b = account("USER_WALLET", "INR", 0);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            UUID e = entry("bad-1");
            posting(e, a, "DEBIT", 500, "INR");
            posting(e, b, "CREDIT", 400, "INR");
        })).rootCause().hasMessageContaining("unbalanced");
        assertThat(postingCount()).isZero();
    }

    @Test
    void singleLeggedEntryIsRejected() {
        UUID a = account("SETTLEMENT", "INR", 0);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> posting(entry("one-leg"), a, "DEBIT", 5, "INR")))
                .rootCause().hasMessageContaining("unbalanced");
    }

    @Test
    void mixedCurrencyEntryIsRejected() {
        UUID a = account("SETTLEMENT", "INR", 0), b = account("USER_WALLET", "USD", 0);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            UUID e = entry("mixed-1");
            posting(e, a, "DEBIT", 500, "INR");
            posting(e, b, "CREDIT", 500, "USD");
        })).rootCause().hasMessageContaining("mixes currencies");
    }

    @Test
    void postingCurrencyMustMatchItsAccount() {
        UUID a = account("SETTLEMENT", "INR", 0), b = account("USER_WALLET", "INR", 0);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            UUID e = entry("cur-1");
            posting(e, a, "DEBIT", 500, "USD");
            posting(e, b, "CREDIT", 500, "USD");
        })).rootCause().hasMessageContaining("differs from its account");
    }

    @Test
    void postingsAndEntriesCannotBeUpdatedOrDeleted() {
        UUID a = account("SETTLEMENT", "INR", 0), b = account("USER_WALLET", "INR", 0);
        tx.executeWithoutResult(s -> {
            UUID e = entry("ao-1");
            posting(e, a, "DEBIT", 500, "INR");
            posting(e, b, "CREDIT", 500, "INR");
        });
        assertThatThrownBy(() -> jdbc.update("UPDATE postings SET amount_minor = 1"))
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM postings"))
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("UPDATE journal_entries SET type = 'REFUND'"))
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM journal_entries"))
                .rootCause().hasMessageContaining("append-only");
    }

    @Test
    void walletBalanceCannotGoNegativeButSettlementCan() {
        UUID wallet = account("USER_WALLET", "INR", 100), settlement = account("SETTLEMENT", "INR", 0);
        assertThatThrownBy(() -> jdbc.update("UPDATE account_balances SET balance_minor = -1 WHERE account_id = ?", wallet))
                .rootCause().hasMessageContaining("balance_not_negative");
        jdbc.update("UPDATE account_balances SET balance_minor = -1000 WHERE account_id = ?", settlement);
    }

    @Test
    void postingAmountMustBePositive() {
        UUID a = account("SETTLEMENT", "INR", 0);
        UUID e = entry("zero-1");
        assertThatThrownBy(() -> posting(e, a, "DEBIT", 0, "INR"))
                .rootCause().hasMessageContaining("amount_minor");
    }

    @Test
    void externalRefIsUnique() {
        entry("dup");
        assertThatThrownBy(() -> entry("dup")).rootCause().hasMessageContaining("external_ref");
    }
}
