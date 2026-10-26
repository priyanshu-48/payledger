package com.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.payledger.common.PaymentEventMessage;
import java.time.Duration;
import java.util.UUID;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** A consumer log line carries the trace id of the request that produced the event (ADR 0016, M8). */
class TraceInLogsTest extends AbstractConsumerTest {

    @Autowired Tracer tracer;

    @Test
    void consumerLogLineHasTheTraceIdFromTheKafkaHeader() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentEventMessage event = event(PaymentEventMessage.typeFor("CREATED"), paymentId, "merchant-x", UUID.randomUUID(), 100);
        Logger listenerLog = (Logger) LoggerFactory.getLogger("com.payledger.ledger.events.PaymentEventListener");
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        listenerLog.addAppender(lines);
        try {
            // the template adds the traceparent header of the active span, as the outbox poller does for stored rows
            Span request = tracer.nextSpan().name("test request").start();
            try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
                publish(event);
            } finally {
                request.end();
            }
            String traceId = request.context().traceId();

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(lines.list)
                    .anySatisfy(l -> {
                        assertThat(l.getFormattedMessage()).contains(paymentId.toString());
                        assertThat(l.getMDCPropertyMap()).containsEntry("traceId", traceId);
                    }));
        } finally {
            listenerLog.detachAppender(lines);
        }
    }
}
