package com.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/** The lag is the broker's view (ADR 0020): it grows while the consumer is stopped and drains when it returns. */
class ConsumerLagMetricsTest extends AbstractConsumerTest {

    @Autowired MeterRegistry registry;
    @Autowired KafkaListenerEndpointRegistry listeners;

    private double lag() {
        return registry.get("kafka.consumer.group.lag").tag("group", "ledger-service").gauge().value();
    }

    @Test
    void lagGrowsWhileTheConsumerIsStoppedAndDrainsAfterItReturns() throws Exception {
        publishRaw("warm-up", "not json");                       // goes to the DLT; makes sure the group has committed
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(lag()).isZero());

        listeners.getListenerContainers().forEach(MessageListenerContainer::stop);
        try {
            for (int i = 0; i < 7; i++) {
                publishRaw("k-" + i, "not json");
            }
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(lag()).isEqualTo(7));
        } finally {
            listeners.getListenerContainers().forEach(MessageListenerContainer::start);
        }

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(lag()).isZero());
    }
}
