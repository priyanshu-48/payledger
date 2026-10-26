package com.payledger.ledger.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account extends NewAwareEntity<UUID> {

    @Id
    private UUID id;
    private String ownerId;
    @Enumerated(EnumType.STRING)
    private AccountType type;
    private String currency;
    @Enumerated(EnumType.STRING)
    private AccountStatus status;
    private Instant createdAt;

    protected Account() {
    }

    public Account(UUID id, String ownerId, AccountType type, String currency) {
        this.id = id;
        this.ownerId = ownerId;
        this.type = type;
        this.currency = currency;
        this.status = AccountStatus.ACTIVE;
        this.createdAt = Instant.now();
    }

    @Override public UUID getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public AccountType getType() { return type; }
    public String getCurrency() { return currency; }
    public AccountStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    public boolean isActive() { return status == AccountStatus.ACTIVE; }

    /** Freeze or unfreeze. Callers hold the account's balance-row lock so this cannot race a transfer. */
    public void changeStatus(AccountStatus newStatus) { this.status = newStatus; }
}
