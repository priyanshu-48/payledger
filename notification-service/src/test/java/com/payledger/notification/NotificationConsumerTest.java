package com.payledger.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payledger.common.LedgerEventMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(properties = {"notification.consumer.enabled=true", "notification.consumer.retry.initial=PT0.1S"})
class NotificationConsumerTest extends AbstractKafkaTest {

    private static final Duration WAIT = Duration.ofSeconds(40);

    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper json;
    @MockitoSpyBean NotificationProcessor processor;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE notifications");
    }

    private LedgerEventMessage event() {
        return new LedgerEventMessage(UUID.randomUUID(), LedgerEventMessage.ENTRY_POSTED, UUID.randomUUID(),
                "TRANSFER", "ref-" + UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "alice", "bob",
                25_050, "INR", Instant.now());
    }

    private void publish(LedgerEventMessage e) throws Exception {
        kafka.send(LedgerEventMessage.TOPIC, e.entryId().toString(), json.writeValueAsString(e)).get();
    }

    private long notifications(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_id = ?", Long.class, eventId);
    }

    @Test
    void oneEventNotifiesTheSenderAndTheReceiver() throws Exception {
        LedgerEventMessage e = event();

        publish(e);

        await().atMost(WAIT).until(() -> notifications(e.eventId()) == 2);
        assertThat(jdbc.queryForObject("SELECT subject FROM notifications WHERE event_id = ? AND recipient = 'alice'",
                String.class, e.eventId())).isEqualTo("You sent 250.50 INR");
        assertThat(jdbc.queryForObject("SELECT subject FROM notifications WHERE event_id = ? AND recipient = 'bob'",
                String.class, e.eventId())).isEqualTo("You received 250.50 INR");
    }

    @Test
    void theSameEventDeliveredManyTimesNotifiesOnce() throws Exception {
        LedgerEventMessage e = event();
        for (int i = 0; i < 5; i++) {
            publish(e);
        }
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                calls.add(pool.submit(() -> processor.process(e)));
            }
            for (Future<?> call : calls) {
                call.get();
            }
        } finally {
            pool.shutdownNow();
        }

        await().atMost(WAIT).until(() -> notifications(e.eventId()) == 2);
        Thread.sleep(1500); // give any stray duplicate time to show up
        assertThat(notifications(e.eventId())).isEqualTo(2);
    }

    @Test
    void anUnreadableMessageIsDeadLetteredAndTheConsumerKeepsGoing() throws Exception {
        String poisonKey = "poison-" + UUID.randomUUID();
        LedgerEventMessage good = event();

        kafka.send(LedgerEventMessage.TOPIC, poisonKey, "this is not json").get();
        publish(good);

        assertThat(deadLetter(poisonKey)).isNotNull();
        await().atMost(WAIT).until(() -> notifications(good.eventId()) == 2);
    }

    @Test
    void aTransientDatabaseFailureIsRetriedAndStoredOnce() throws Exception {
        LedgerEventMessage e = event();
        doThrow(new TransientDataAccessResourceException("db hiccup 1"))
                .doThrow(new TransientDataAccessResourceException("db hiccup 2"))
                .doCallRealMethod()
                .when(processor).process(any());

        publish(e);

        await().atMost(WAIT).until(() -> notifications(e.eventId()) == 2);
    }

    private ConsumerRecord<String, String> deadLetter(String key) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "notif-dlt-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        long deadline = System.nanoTime() + WAIT.toNanos();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(LedgerEventMessage.DEAD_LETTER_TOPIC));
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(r.key())) {
                        return r;
                    }
                }
            }
        }
        throw new AssertionError("no dead letter with key " + key);
    }
}
