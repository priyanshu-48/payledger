package com.payledger.payment.outbox;

import com.payledger.common.PaymentEventMessage;
import com.payledger.payment.outbox.OutboxStore.Claimed;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Publishes outbox rows to Kafka (ADR 0013): claim (own commit) -> publish and wait for the broker's ack
 * (no DB transaction open) -> mark published (own commit). Failures leave the row leased; it becomes
 * claimable again when the lease expires, so nothing is lost and nothing is published before it committed.
 */
@Component
@ConditionalOnProperty(name = "payledger.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxStore store;
    private final KafkaTemplate<String, String> kafka;
    private final int batchSize;
    private final Duration lease;
    private final Duration sendTimeout;

    public OutboxPoller(OutboxStore store, KafkaTemplate<String, String> kafka,
                        @Value("${payledger.outbox.batch-size}") int batchSize,
                        @Value("${payledger.outbox.lease}") Duration lease,
                        @Value("${payledger.outbox.send-timeout}") Duration sendTimeout) {
        this.store = store;
        this.kafka = kafka;
        this.batchSize = batchSize;
        this.lease = lease;
        this.sendTimeout = sendTimeout;
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
                sends.put(row.id(), kafka.send(PaymentEventMessage.TOPIC, row.aggregateId().toString(), row.payload()));
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
}
