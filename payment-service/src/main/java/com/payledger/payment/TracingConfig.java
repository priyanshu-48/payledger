package com.payledger.payment;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapPropagator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot's default left the OpenTelemetry propagator empty here (fields=[]), so no traceparent was ever
 * written to Kafka headers or the outbox. Declaring the W3C propagator explicitly makes propagation certain (ADR 0016).
 */
@Configuration
class TracingConfig {

    @Bean
    TextMapPropagator w3cTraceContextPropagator() {
        return W3CTraceContextPropagator.getInstance();
    }
}
