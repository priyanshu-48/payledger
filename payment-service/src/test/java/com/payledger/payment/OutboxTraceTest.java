package com.payledger.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.payledger.common.PaymentEventMessage;
import com.payledger.outbox.OutboxPoller;
import com.payledger.payment.domain.Payment;
import com.payledger.payment.service.PaymentTransitions;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR 0016: the trace of the request that changed a payment must survive the outbox. The publish happens
 * later on the poller's thread, which has no active span of its own, yet the Kafka record must carry the
 * request's trace id.
 */
@SpringBootTest(properties = {"payledger.outbox.enabled=true", "payledger.outbox.interval=PT1H"})
class OutboxTraceTest extends AbstractKafkaTest {

    @Autowired Tracer tracer;
    @Autowired PaymentTransitions transitions;
    @Autowired OutboxPoller poller;
    @Autowired JdbcTemplate jdbc;

    @Test
    void theTraceOfTheRequestTravelsThroughTheOutboxIntoTheKafkaHeaders() throws Exception {
        jdbc.execute("TRUNCATE outbox");
        Payment payment = new Payment(UUID.randomUUID(), "m", UUID.randomUUID(), 1_000, "INR", "c", "k-" + UUID.randomUUID(), "h");

        Span request = tracer.nextSpan().name("test request").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
            transitions.insertNew(payment); // as the HTTP handler would
        } finally {
            request.end();
        }
        String traceId = request.context().traceId();

        String stored = jdbc.queryForObject("SELECT trace_parent FROM outbox", String.class);
        assertThat(stored).as("stored with the row").contains(traceId);

        assertThat(poller.runOnce()).isEqualTo(1); // later, on this thread, with no active span

        ConsumerRecord<String, String> record = readRecord(payment.getId().toString());
        String header = new String(record.headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8);
        assertThat(header).as("Kafka record header continues the request's trace").contains(traceId);
        assertThat(record.value()).contains(PaymentEventMessage.typeFor("CREATED"));
    }

    private ConsumerRecord<String, String> readRecord(String key) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "trace-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(PaymentEventMessage.TOPIC));
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(r.key())) {
                        return r;
                    }
                }
            }
        }
        throw new AssertionError("no record with key " + key + " arrived");
    }
}
