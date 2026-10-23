package com.payledger.payment.outbox;

import com.payledger.common.PaymentEventMessage;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Creates the topic on startup if missing (idempotent; ledger-service declares the same topic). */
@Configuration
@ConditionalOnProperty(name = "payledger.outbox.enabled", havingValue = "true", matchIfMissing = true)
class OutboxTopicConfig {

    @Bean
    NewTopic paymentsEventsTopic() {
        return TopicBuilder.name(PaymentEventMessage.TOPIC).partitions(3).replicas(1).build();
    }
}
