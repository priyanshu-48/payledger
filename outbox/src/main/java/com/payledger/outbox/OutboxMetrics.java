package com.payledger.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.DoubleSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Outbox lag gauges (ADR 0020), evaluated at scrape time. A failing query reports NaN rather than breaking the scrape.
 */
public class OutboxMetrics {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

    public OutboxMetrics(OutboxStore store, MeterRegistry registry) {
        Gauge.builder("outbox.oldest.unpublished.age.seconds", safe(store::oldestUnpublishedAgeSeconds))
                .description("Age of the oldest outbox row that is not yet on Kafka")
                .baseUnit("seconds").register(registry);
        Gauge.builder("outbox.unpublished", safe(store::unpublishedCount))
                .description("Outbox rows not yet on Kafka").register(registry);
    }

    private static java.util.function.Supplier<Number> safe(DoubleSupplier query) {
        return () -> {
            try {
                return query.getAsDouble();
            } catch (RuntimeException e) {
                log.warn("outbox metric query failed: {}", e.toString());
                return Double.NaN;
            }
        };
    }
}
