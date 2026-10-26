package com.payledger.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Import this from a service ({@code @Import(OutboxConfiguration.class)}). The store and writer always exist so
 * rows are written whatever the setting; only the poller is switched by {@code payledger.outbox.enabled}.
 */
@Configuration(proxyBeanMethods = false)
public class OutboxConfiguration {

    @Bean
    OutboxStore outboxStore(JdbcTemplate jdbc) {
        return new OutboxStore(jdbc);
    }

    @Bean
    OutboxMetrics outboxMetrics(OutboxStore store, MeterRegistry registry) {
        return new OutboxMetrics(store, registry);
    }

    @Bean
    OutboxWriter outboxWriter(OutboxStore store, Tracer tracer, Propagator propagator) {
        return new OutboxWriter(store, tracer, propagator);
    }

    @Bean
    @ConditionalOnProperty(name = "payledger.outbox.enabled", havingValue = "true", matchIfMissing = true)
    OutboxPoller outboxPoller(OutboxStore store, KafkaTemplate<String, String> kafka, Tracer tracer,
                              Propagator propagator,
                              @Value("${payledger.outbox.topic}") String topic,
                              @Value("${payledger.outbox.batch-size}") int batchSize,
                              @Value("${payledger.outbox.lease}") Duration lease,
                              @Value("${payledger.outbox.send-timeout}") Duration sendTimeout) {
        return new OutboxPoller(store, kafka, topic, batchSize, lease, sendTimeout, tracer, propagator);
    }
}
