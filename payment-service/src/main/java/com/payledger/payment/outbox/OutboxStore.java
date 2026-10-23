package com.payledger.payment.outbox;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The poller's SQL (ADR 0013). Each method is one autocommit statement: no transaction is ever open
 * while the poller talks to Kafka.
 */
@Repository
public class OutboxStore {

    public record Claimed(UUID id, UUID aggregateId, String type, String payload) {
    }

    private final JdbcTemplate jdbc;

    public OutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Leases up to {@code limit} publishable rows and returns them. A row is publishable when it is unpublished,
     * not currently leased, and no earlier unpublished row of the same payment exists (keeps per-payment order,
     * even with several pollers). SKIP LOCKED lets concurrent pollers pick different rows instead of waiting.
     */
    public List<Claimed> claim(int limit, Duration lease) {
        return jdbc.query("""
                UPDATE outbox SET claimed_until = now() + ? * interval '1 second', attempts = attempts + 1
                WHERE id IN (
                    SELECT o.id FROM outbox o
                    WHERE o.published_at IS NULL
                      AND (o.claimed_until IS NULL OR o.claimed_until < now())
                      AND NOT EXISTS (SELECT 1 FROM outbox e
                                      WHERE e.aggregate_id = o.aggregate_id AND e.published_at IS NULL AND e.seq < o.seq)
                    ORDER BY o.seq
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED)
                RETURNING id, aggregate_id, event_type, payload::text AS payload, seq
                """,
                (rs, i) -> new Claimed(rs.getObject("id", UUID.class), rs.getObject("aggregate_id", UUID.class),
                        rs.getString("event_type"), rs.getString("payload")),
                lease.toMillis() / 1000.0, limit)
                .stream().toList();
    }

    public void markPublished(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.update("UPDATE outbox SET published_at = now() WHERE id = ANY (?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids.toArray())));
    }

    public long unpublishedCount() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NULL", Long.class);
    }
}
