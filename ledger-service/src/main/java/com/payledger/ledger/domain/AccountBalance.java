package com.payledger.ledger.domain;

import com.payledger.common.Direction;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** Current balance = credits - debits (ADR 0003). The @Version column is for the optimistic variant (M2). */
@Entity
@Table(name = "account_balances")
public class AccountBalance extends NewAwareEntity<UUID> {

    @Id
    private UUID accountId;
    private long balanceMinor;
    private boolean floorZero;
    @Version
    private long version;
    private Instant updatedAt;

    protected AccountBalance() {
    }

    public AccountBalance(UUID accountId, boolean floorZero) {
        this.accountId = accountId;
        this.floorZero = floorZero;
        this.updatedAt = Instant.now();
    }

    /** DEBIT lowers the balance, CREDIT raises it. */
    public void apply(Direction direction, long amountMinor) {
        balanceMinor = direction == Direction.CREDIT
                ? Math.addExact(balanceMinor, amountMinor)
                : Math.subtractExact(balanceMinor, amountMinor);
        updatedAt = Instant.now();
    }

    @Override public UUID getId() { return accountId; }
    public UUID getAccountId() { return accountId; }
    public long getBalanceMinor() { return balanceMinor; }
    public boolean isFloorZero() { return floorZero; }
    public long getVersion() { return version; }
}
