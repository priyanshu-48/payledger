package com.payledger.outbox;

import com.payledger.outbox.OutboxStore.Claimed;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Publishes outbox rows to Kafka (ADR 0013): claim (own commit) -> publish and wait for the broker's ack
 * (no DB transaction open) -> mark published (own commit). Failures leave the row leased; it becomes
 * claimable again when the lease expires, so nothing is lost and nothing is published before it committed.
 * Each send runs inside a span that continues the trace stored with the row (ADR 0016).
 */
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxStore store;
    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final int batchSize;
    private final Duration lease;
    private final Duration sendTimeout;
    private final Tracer tracer;
    private final Propagator propagator;

    public OutboxPoller(OutboxStore store, KafkaTemplate<String, String> kafka, String topic, int batchSize,
                        Duration lease, Duration sendTimeout, Tracer tracer, Propagator propagator) {
        this.store = store;
        this.kafka = kafka;
        this.topic = topic;
        this.batchSize = batchSize;
        this.lease = lease;
        this.sendTimeout = sendTimeout;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Scheduled(initialDelayString = "${payledger.outbox.interval}", fixedDelayString = "${payledger.outbox.interval}")
    void scheduled() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("outbox sweep failed, will retry", e);
        }
    }

    /** One sweep; returns how many events were published and marked. */
    public int runOnce() {
        List<Claimed> batch = store.claim(batchSize, lease);
        if (batch.isEmpty()) {
            return 0;
        }
        Map<UUID, CompletableFuture<SendResult<String, String>>> sends = new LinkedHashMap<>();
        for (Claimed row : batch) {
            try {
                sends.put(row.id(), send(row));
            } catch (RuntimeException e) {
                // Broker unreachable (metadata fetch timed out). The rest would fail the same way and each
                // attempt blocks, so give up this sweep; unsent rows stay leased and are retried later.
                log.warn("Kafka unavailable: abandoning this sweep after {} of {} rows", sends.size(), batch.size(), e);
                break;
            }
        }
        List<UUID> acknowledged = new ArrayList<>();
        sends.forEach((id, future) -> {
            try {
                future.get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                acknowledged.add(id);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("outbox event {} not acknowledged by Kafka, will retry after the lease", id, e);
            }
        });
        store.markPublished(acknowledged);
        return acknowledged.size();
    }

    private CompletableFuture<SendResult<String, String>> send(Claimed row) {
        Span span = startSpan(row.traceParent());
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return kafka.send(topic, row.aggregateId().toString(), row.payload());
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /** A child of the stored trace if there is one, otherwise a fresh root. */
    private Span startSpan(String traceParent) {
        if (traceParent == null) {
            return tracer.nextSpan().name("outbox publish").start();
        }
        Map<String, String> carrier = Map.of(OutboxWriter.TRACEPARENT, traceParent);
        return propagator.extract(carrier, (c, key) -> c.get(key)).name("outbox publish").start();
    }
}
