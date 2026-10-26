package com.payledger.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payledger.common.LedgerEventMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads ledger.events. Parsing happens here so an unreadable message fails inside the listener and is
 * dead-lettered like any other poison message. Offsets are committed per record, after the processor's transaction.
 */
@Component
@ConditionalOnProperty(name = "notification.consumer.enabled", havingValue = "true", matchIfMissing = true)
class NotificationListener {

    private final NotificationProcessor processor;
    private final ObjectMapper json;

    NotificationListener(NotificationProcessor processor, ObjectMapper json) {
        this.processor = processor;
        this.json = json;
    }

    @KafkaListener(id = "ledger-events", topics = LedgerEventMessage.TOPIC, groupId = "notification-service",
            concurrency = "${notification.consumer.concurrency:3}")
    void on(String payload) throws JsonProcessingException {
        processor.process(json.readValue(payload, LedgerEventMessage.class));
    }
}
