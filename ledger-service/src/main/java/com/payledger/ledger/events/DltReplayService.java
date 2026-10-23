package com.payledger.ledger.events;

import com.payledger.common.PaymentEventMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Service;

/**
 * Moves dead-lettered events back to their original topic once the cause is fixed (ADR 0014). Reads the DLT
 * with a dedicated group and commits per record, so each call only replays records it has not replayed yet;
 * a record that fails again simply lands in the DLT again.
 */
@Service
@ConditionalOnProperty(name = "ledger.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class DltReplayService {

    static final String GROUP = "ledger-dlt-replay";

    private final ConsumerFactory<String, String> consumers;
    private final KafkaTemplate<String, String> kafka;

    public DltReplayService(ConsumerFactory<String, String> consumers, KafkaTemplate<String, String> kafka) {
        this.consumers = consumers;
        this.kafka = kafka;
    }

    /** Returns how many dead letters were republished. */
    public synchronized int replay() throws Exception {
        int replayed = 0;
        try (Consumer<String, String> consumer = consumers.createConsumer(GROUP, "replay")) {
            // assign() instead of subscribe(): no group join/rebalance delay; offsets are still committed under GROUP
            List<TopicPartition> partitions = consumer.partitionsFor(PaymentEventMessage.DEAD_LETTER_TOPIC).stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
            consumer.assign(partitions);
            // Only what is in the DLT right now. A replayed message that fails again lands back in the DLT;
            // reading to "empty" would chase it forever, so those new arrivals wait for the next call.
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline && partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    TopicPartition tp = new TopicPartition(record.topic(), record.partition());
                    if (record.offset() >= end.get(tp)) {
                        continue;
                    }
                    kafka.send(originalTopic(record), record.key(), record.value()).get(10, TimeUnit.SECONDS);
                    consumer.commitSync(Map.of(tp, new OffsetAndMetadata(record.offset() + 1)));
                    replayed++;
                }
            }
        }
        return replayed;
    }

    private static String originalTopic(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC);
        return header == null ? PaymentEventMessage.TOPIC : new String(header.value(), StandardCharsets.UTF_8);
    }
}
