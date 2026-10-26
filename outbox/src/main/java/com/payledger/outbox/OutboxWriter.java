package com.payledger.outbox;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Called inside a business transaction to record "this event must be published". It also remembers the
 * current trace (W3C traceparent) so the poller, which runs later on another thread, can continue it (ADR 0016).
 */
public class OutboxWriter {

    static final String TRACEPARENT = "traceparent";

    private final OutboxStore store;
    private final Tracer tracer;
    private final Propagator propagator;

    public OutboxWriter(OutboxStore store, Tracer tracer, Propagator propagator) {
        this.store = store;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    public void write(UUID eventId, UUID aggregateId, String eventType, String payloadJson) {
        store.insert(eventId, aggregateId, eventType, payloadJson, currentTraceParent());
    }

    private String currentTraceParent() {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return null; // no active span (for example in a plain unit test)
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(context, carrier, (c, key, value) -> c.put(key, value));
        return carrier.get(TRACEPARENT);
    }
}
