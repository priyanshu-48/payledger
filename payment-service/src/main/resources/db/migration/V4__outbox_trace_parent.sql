-- ADR 0016: the W3C traceparent of the request that caused the event, so the poller can continue the trace.
ALTER TABLE outbox ADD COLUMN trace_parent TEXT;
