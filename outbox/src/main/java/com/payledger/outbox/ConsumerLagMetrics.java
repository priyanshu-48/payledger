package com.payledger.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code kafka.consumer.group.lag{group,topic}}: latest offset minus the group's committed offset, summed over the
 * topic's partitions, asked of the broker (ADR 0020). The consumer's own records-lag metric is not used because it
 * disappears with the consumer, which is exactly when lag matters. NaN when the broker cannot be asked.
 */
public class ConsumerLagMetrics implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConsumerLagMetrics.class);
    private static final long CACHE_MILLIS = 5_000;
    private static final long TIMEOUT_SECONDS = 3;

    private final Admin admin;
    private final String group;
    private final String topic;
    private long fetchedAt;
    private double cached = Double.NaN;

    public ConsumerLagMetrics(Map<String, Object> kafkaProperties, String group, String topic, MeterRegistry registry) {
        Map<String, Object> props = new HashMap<>(kafkaProperties);
        props.put("client.id", "lag-" + group);
        int millis = (int) Duration.ofSeconds(TIMEOUT_SECONDS).toMillis();
        props.put("request.timeout.ms", millis);
        props.put("default.api.timeout.ms", millis);
        this.admin = Admin.create(props);
        this.group = group;
        this.topic = topic;
        Gauge.builder("kafka.consumer.group.lag", this, ConsumerLagMetrics::lag)
                .tag("group", group).tag("topic", topic)
                .description("Messages the group has not yet committed").register(registry);
    }

    synchronized double lag() {
        long now = System.currentTimeMillis();
        if (now - fetchedAt < CACHE_MILLIS) {
            return cached;
        }
        fetchedAt = now;
        try {
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(group)
                    .partitionsToOffsetAndMetadata().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
            admin.describeTopics(java.util.List.of(topic)).allTopicNames().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .get(topic).partitions()
                    .forEach(p -> latest.put(new TopicPartition(topic, p.partition()), OffsetSpec.latest()));
            double total = 0;
            for (var e : admin.listOffsets(latest).all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).entrySet()) {
                OffsetAndMetadata c = committed.get(e.getKey());
                total += e.getValue().offset() - (c == null ? 0 : c.offset());
            }
            cached = total;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("consumer lag query failed for group {}: {}", group, e.toString());
            cached = Double.NaN;
        }
        return cached;
    }

    @Override
    public void close() {
        admin.close(Duration.ofSeconds(1));
    }
}
