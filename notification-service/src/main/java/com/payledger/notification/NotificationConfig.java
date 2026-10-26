package com.payledger.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.payledger.common.LedgerEventMessage;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapPropagator;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration
class NotificationConfig {

    /** Spring Boot's default left the OpenTelemetry propagator empty, so traces never reached us (ADR 0016). */
    @Bean
    TextMapPropagator w3cTraceContextPropagator() {
        return W3CTraceContextPropagator.getInstance();
    }

    /** Failure policy (ADR 0015): unreadable messages go straight to the DLT, anything else is retried first. */
    @Configuration
    @ConditionalOnProperty(name = "notification.consumer.enabled", havingValue = "true", matchIfMissing = true)
    static class Consumer {

        @Bean
        NewTopic ledgerEventsTopic() {
            return TopicBuilder.name(LedgerEventMessage.TOPIC).partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic ledgerEventsDeadLetterTopic() {
            return TopicBuilder.name(LedgerEventMessage.DEAD_LETTER_TOPIC).partitions(3).replicas(1).build();
        }

        @Bean
        DefaultErrorHandler kafkaErrorHandler(KafkaOperations<String, String> kafka,
                                              @Value("${notification.consumer.retry.initial}") Duration initial,
                                              @Value("${notification.consumer.retry.max-retries}") int maxRetries) {
            DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
                    (record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
            ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(maxRetries);
            backOff.setInitialInterval(initial.toMillis());
            backOff.setMultiplier(2.0);
            backOff.setMaxInterval(Duration.ofSeconds(10).toMillis());
            DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
            handler.addNotRetryableExceptions(JsonProcessingException.class, IllegalArgumentException.class);
            return handler;
        }
    }
}
