package com.payledger.ledger.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payledger.common.PaymentEventMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads payments.events. The payload is parsed here rather than by a Kafka deserializer so a bad message
 * fails inside the listener and is handled (and dead-lettered) like any other poison message.
 * Offsets are committed per record after this method returns, i.e. after the processor's transaction.
 */
@Component
@ConditionalOnProperty(name = "ledger.consumer.enabled", havingValue = "true", matchIfMissing = true)
class PaymentEventListener {

    static final String LISTENER_ID = "payments-events";

    private final PaymentEventProcessor processor;
    private final ObjectMapper json;

    PaymentEventListener(PaymentEventProcessor processor, ObjectMapper json) {
        this.processor = processor;
        this.json = json;
    }

    @KafkaListener(id = LISTENER_ID, topics = PaymentEventMessage.TOPIC, groupId = "ledger-service",
            concurrency = "${ledger.consumer.concurrency:3}")
    void on(String payload) throws JsonProcessingException {
        processor.process(json.readValue(payload, PaymentEventMessage.class));
    }
}
