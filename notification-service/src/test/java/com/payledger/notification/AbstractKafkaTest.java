package com.payledger.notification;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.kafka.KafkaContainer;

/** Adds one shared Kafka broker (per JVM) on top of the shared Postgres. */
abstract class AbstractKafkaTest extends AbstractPostgresTest {

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    static {
        KAFKA.start();
    }
}
