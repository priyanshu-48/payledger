package com.payledger.ledger.domain;

import com.payledger.common.Direction;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "postings")
public class Posting extends NewAwareEntity<UUID> {

    @Id
    private UUID id;
    private UUID entryId;
    private UUID accountId;
    @Enumerated(EnumType.STRING)
    private Direction direction;
    private long amountMinor;
    private String currency;

    protected Posting() {
    }

    public Posting(UUID entryId, UUID accountId, Direction direction, long amountMinor, String currency) {
        this.id = UUID.randomUUID();
        this.entryId = entryId;
        this.accountId = accountId;
        this.direction = direction;
        this.amountMinor = amountMinor;
        this.currency = currency;
    }

    @Override public UUID getId() { return id; }
    public UUID getEntryId() { return entryId; }
    public UUID getAccountId() { return accountId; }
    public Direction getDirection() { return direction; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
}
