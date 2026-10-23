package com.payledger.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.payledger.common.PaymentEventMessage;
import com.payledger.payment.outbox.OutboxPoller;
import com.payledger.payment.outbox.OutboxStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * The poller against a real broker. The scheduled sweep is parked (interval 1 h) so each test drives
 * runOnce() itself. Rows are inserted directly; payloads are tiny JSON documents carrying the event id.
 */
@SpringBootTest(properties = {"payledger.outbox.enabled=true", "payledger.outbox.interval=PT1H"})
class OutboxPollerTest extends AbstractKafkaTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxStore store;
    @Autowired KafkaTemplate<String, String> kafka;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE outbox");
    }

    private OutboxPoller poller(OutboxStore s, KafkaTemplate<String, String> k, int batch, Duration lease) {
        return new OutboxPoller(s, k, batch, lease, Duration.ofSeconds(5));
    }

    private UUID insertRow(UUID aggregate, int n) {
        UUID id = UUID.randomUUID();
        String payload = "{\"eventId\":\"" + id + "\",\"n\":" + n + ",\"aggregate\":\"" + aggregate + "\"}";
        jdbc.update("INSERT INTO outbox (id, aggregate_id, event_type, payload) VALUES (?, ?, 'test', ?::jsonb)",
                id, aggregate, payload);
        return id;
    }

    /** Reads the topic from the beginning until {@code expected} of the given ids were seen (or time runs out). */
    private List<ConsumerRecord<String, String>> consume(Set<UUID> ids, int expected, Duration timeout) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> seen = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(PaymentEventMessage.TOPIC));
            while (System.nanoTime() < deadline && seen.size() < expected) {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (ids.stream().anyMatch(id -> r.value().contains(id.toString()))) {
                        seen.add(r);
                    }
                });
            }
            // keep reading briefly to catch unexpected extra copies
            long settle = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (System.nanoTime() < settle) {
                consumer.poll(Duration.ofMillis(300)).forEach(r -> {
                    if (ids.stream().anyMatch(id -> r.value().contains(id.toString()))) {
                        seen.add(r);
                    }
                });
            }
        }
        return seen;
    }

    /** Postgres stores jsonb normalised (spaces, key order), so read fields by parsing, not by regex. */
    private static String field(ConsumerRecord<String, String> record, String name) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(record.value()).get(name).asText();
        } catch (Exception e) {
            throw new IllegalStateException(record.value(), e);
        }
    }

    private void sweepUntilDrained(OutboxPoller poller) {
        for (int i = 0; i < 50 && store.unpublishedCount() > 0; i++) {
            poller.runOnce();
        }
    }

    @Test
    void publishesEveryRowExactlyOnceAndKeepsEachPaymentsOrder() {
        List<UUID> aggregates = new ArrayList<>();
        for (int a = 0; a < 100; a++) {
            aggregates.add(UUID.randomUUID());
        }
        Set<UUID> ids = new HashSet<>();
        for (int n = 0; n < 5; n++) {          // 5 events per payment, interleaved across payments
            for (UUID aggregate : aggregates) {
                ids.add(insertRow(aggregate, n));
            }
        }

        sweepUntilDrained(poller(store, kafka, 100, Duration.ofSeconds(10)));

        List<ConsumerRecord<String, String>> records = consume(ids, 500, Duration.ofSeconds(30));
        assertThat(records).hasSize(500);
        assertThat(records.stream().map(r -> field(r, "eventId")).toList())
                .containsExactlyInAnyOrderElementsOf(ids.stream().map(UUID::toString).toList());
        Map<String, Integer> lastSeen = new HashMap<>();
        for (ConsumerRecord<String, String> r : records) { // per-key order as read from its partition
            int n = Integer.parseInt(field(r, "n"));
            Integer previous = lastSeen.put(r.key(), n);
            assertThat(previous == null || previous < n).as("payment %s event %d after %s", r.key(), n, previous).isTrue();
        }
        assertThat(store.unpublishedCount()).isZero();
    }

    @Test
    void twoPollersRunningTogetherNeverPublishTheSameRowTwice() throws Exception {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 300; i++) {
            ids.add(insertRow(UUID.randomUUID(), 0));
        }
        OutboxPoller a = poller(store, kafka, 20, Duration.ofSeconds(30));
        OutboxPoller b = poller(store, kafka, 20, Duration.ofSeconds(30));
        Thread ta = new Thread(() -> sweepUntilDrained(a));
        Thread tb = new Thread(() -> sweepUntilDrained(b));
        ta.start();
        tb.start();
        ta.join();
        tb.join();

        List<ConsumerRecord<String, String>> records = consume(ids, 300, Duration.ofSeconds(30));
        assertThat(records).as("each row on the topic exactly once").hasSize(300);
        assertThat(store.unpublishedCount()).isZero();
    }

    @Test
    void whenKafkaIsDownNothingIsLostAndEverythingDrainsAfterwards() throws Exception {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            ids.add(insertRow(UUID.randomUUID(), 0));
        }
        Map<String, Object> dead = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 500,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 1500,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 500);
        KafkaTemplate<String, String> unreachable = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(dead));

        int published = new OutboxPoller(store, unreachable, 100, Duration.ofSeconds(1), Duration.ofSeconds(3)).runOnce();

        assertThat(published).isZero();
        assertThat(store.unpublishedCount()).as("every row is still pending").isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT min(attempts) FROM outbox", Integer.class)).isEqualTo(1);

        Thread.sleep(1200); // the lease expires, as it would while an operator brings Kafka back
        sweepUntilDrained(poller(store, kafka, 100, Duration.ofSeconds(1)));

        assertThat(consume(ids, 20, Duration.ofSeconds(30))).hasSize(20);
        assertThat(store.unpublishedCount()).isZero();
    }

    @Test
    void aCrashBetweenPublishAndMarkRepublishesTheSameEventId() throws Exception {
        UUID id = insertRow(UUID.randomUUID(), 0);
        AtomicBoolean crashed = new AtomicBoolean(false);
        OutboxStore crashingOnce = new OutboxStore(jdbc) {
            @Override
            public void markPublished(Collection<UUID> ids) {
                if (crashed.compareAndSet(false, true)) {
                    throw new IllegalStateException("process died after publishing, before marking");
                }
                super.markPublished(ids);
            }
        };
        OutboxPoller poller = poller(crashingOnce, kafka, 10, Duration.ofSeconds(1));

        try {
            poller.runOnce();
        } catch (IllegalStateException expected) {
            // the "crash"
        }
        assertThat(store.unpublishedCount()).as("not marked, so still pending").isEqualTo(1);
        Thread.sleep(1200);
        assertThat(poller.runOnce()).isEqualTo(1);

        List<ConsumerRecord<String, String>> records = consume(Set.of(id), 2, Duration.ofSeconds(30));
        assertThat(records).as("at-least-once: the same event reached Kafka twice").hasSize(2);
        assertThat(records.get(0).value()).isEqualTo(records.get(1).value()).contains(id.toString());
        assertThat(store.unpublishedCount()).isZero();
    }
}
