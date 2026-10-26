package com.payledger.ledger;

import com.payledger.common.LedgerEventMessage;
import com.payledger.outbox.OutboxConfiguration;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapPropagator;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@Import(OutboxConfiguration.class)
class LedgerConfig {

    /** Spring Boot's default left the OpenTelemetry propagator empty, so traces never reached Kafka (ADR 0016). */
    @Bean
    TextMapPropagator w3cTraceContextPropagator() {
        return W3CTraceContextPropagator.getInstance();
    }

    /** Created on startup if missing; only when the outbox poller (the publisher) is on. */
    @Bean
    @ConditionalOnProperty(name = "payledger.outbox.enabled", havingValue = "true", matchIfMissing = true)
    NewTopic ledgerEventsTopic() {
        return TopicBuilder.name(LedgerEventMessage.TOPIC).partitions(3).replicas(1).build();
    }
}
