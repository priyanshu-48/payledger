package com.payledger.ledger.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "journal_entries")
public class JournalEntry extends NewAwareEntity<UUID> {

    @Id
    private UUID id;
    private String externalRef;
    @Enumerated(EnumType.STRING)
    private EntryType type;
    private UUID reversesEntryId;
    private Instant createdAt;

    protected JournalEntry() {
    }

    public JournalEntry(UUID id, String externalRef, EntryType type) {
        this.id = id;
        this.externalRef = externalRef;
        this.type = type;
        this.createdAt = Instant.now();
    }

    @Override public UUID getId() { return id; }
    public String getExternalRef() { return externalRef; }
    public EntryType getType() { return type; }
    public UUID getReversesEntryId() { return reversesEntryId; }
    public Instant getCreatedAt() { return createdAt; }
}
