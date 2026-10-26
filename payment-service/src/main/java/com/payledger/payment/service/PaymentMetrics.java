package com.payledger.payment.service;

import com.payledger.payment.domain.PaymentStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code payments.by.status{status}}: one gauge per status, always present (zero when empty), read from the
 * database so it is right after a restart. One GROUP BY serves all statuses, cached for a second so a scrape
 * does not run seven queries (ADR 0020).
 */
@Component
public class PaymentMetrics {

    private static final long CACHE_MILLIS = 1_000;

    private final JdbcTemplate jdbc;
    private long fetchedAt;
    private Map<PaymentStatus, Double> counts = new EnumMap<>(PaymentStatus.class);

    public PaymentMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        for (PaymentStatus status : PaymentStatus.values()) {
            Gauge.builder("payments.by.status", this, m -> m.count(status))
                    .tag("status", status.name()).description("Payments currently in each status").register(registry);
        }
    }

    private synchronized double count(PaymentStatus status) {
        long now = System.currentTimeMillis();
        if (now - fetchedAt >= CACHE_MILLIS) {
            Map<PaymentStatus, Double> fresh = new EnumMap<>(PaymentStatus.class);
            jdbc.query("SELECT status, count(*) AS n FROM payments GROUP BY status",
                    rs -> { fresh.put(PaymentStatus.valueOf(rs.getString("status")), rs.getDouble("n")); });
            counts = fresh;
            fetchedAt = now;
        }
        return counts.getOrDefault(status, 0.0);
    }
}
