package com.payledger.payment.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Insert-only from the application's point of view. published_at, claimed_until and attempts belong to
 * the poller, which updates them with SQL (OutboxStore), so they are not mapped here.
 */
@Entity
@Table(name = "outbox")
public class OutboxEvent extends NewAwareEntity<UUID> {

    @Id
    private UUID id;
    private UUID aggregateId;
    private String eventType;
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;
    private Instant createdAt;

    protected OutboxEvent() {
    }

    public OutboxEvent(UUID id, UUID aggregateId, String eventType, String payload) {
        this.id = id;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    @Override public UUID getId() { return id; }
    public UUID getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
}
