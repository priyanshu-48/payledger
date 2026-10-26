package com.payledger.ledger.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.payledger.common.CurrencyMismatchException;
import com.payledger.common.InsufficientFundsException;
import com.payledger.common.PaymentEventMessage;
import com.payledger.ledger.events.PaymentEventProcessor.MerchantNotFoundException;
import com.payledger.ledger.service.LedgerExceptions.AccountNotActiveException;
import com.payledger.ledger.service.LedgerExceptions.AccountNotFoundException;
import com.payledger.ledger.service.LedgerExceptions.IdempotencyConflictException;
import com.payledger.ledger.service.LedgerExceptions.InvalidTransferException;
import com.payledger.outbox.ConsumerLagMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.kafka.core.KafkaAdmin;
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

/**
 * Failure policy of the consumer (ADR 0014). Business failures cannot succeed by waiting, so they go to
 * payments.events.DLT at once; anything else (a database hiccup) is retried with backoff first.
 * Spring Boot attaches a CommonErrorHandler bean to the listener container factory automatically.
 */
@Configuration
@ConditionalOnProperty(name = "ledger.consumer.enabled", havingValue = "true", matchIfMissing = true)
class KafkaConsumerConfig {

    @Bean
    NewTopic paymentsEventsTopic() {
        return TopicBuilder.name(PaymentEventMessage.TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic paymentsEventsDeadLetterTopic() {
        return TopicBuilder.name(PaymentEventMessage.DEAD_LETTER_TOPIC).partitions(3).replicas(1).build();
    }

    @Bean(destroyMethod = "close")
    ConsumerLagMetrics consumerLag(KafkaAdmin admin, MeterRegistry registry,
                                   @Value("${spring.kafka.consumer.group-id}") String group) {
        return new ConsumerLagMetrics(admin.getConfigurationProperties(), group, PaymentEventMessage.TOPIC, registry);
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaOperations<String, String> kafka,
                                          @Value("${ledger.consumer.retry.initial}") Duration initial,
                                          @Value("${ledger.consumer.retry.max-retries}") int maxRetries) {
        // same partition number on the DLT, so a payment's dead letters stay together and ordered
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(maxRetries);
        backOff.setInitialInterval(initial.toMillis());
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(Duration.ofSeconds(10).toMillis());
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(InsufficientFundsException.class, CurrencyMismatchException.class,
                AccountNotFoundException.class, AccountNotActiveException.class, IdempotencyConflictException.class,
                InvalidTransferException.class, MerchantNotFoundException.class, JsonProcessingException.class,
                IllegalArgumentException.class);
        return handler;
    }
}
