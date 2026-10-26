# ADR 0016: Distributed tracing across the outbox
- Status: accepted · Date: 2026-10-26
## Context
SPEC 6.9: trace ids flow from the HTTP request into Kafka headers and the consumer logs; M5 is done when the end-to-end flow is visible in traces. The outbox breaks the usual propagation: the Kafka publish happens later, on the poller thread, outside the request that caused it.
## Decision
- Micrometer Tracing with the OpenTelemetry bridge; spans exported over OTLP to Jaeger (docker compose). Sampling 100% (demo scale). Log lines carry service, trace id and span id.
- When a service writes an outbox row it stores the current W3C `traceparent` in the `trace_parent` column. The poller extracts it, opens a child span on that context, and sends the record inside it, so Spring Kafka's producer observation puts the same trace id in the record headers.
- Spring Kafka listener observation continues the trace in consumers; the ledger's own outbox row captures it again for the next hop. One trace therefore covers: HTTP request, payment-service, Kafka, ledger-service, Kafka, notification-service.
- Tests run with sampling on and the exporter off.
## Consequences
A trace spans minutes if the outbox is backed up (the child span starts when published). Jaeger keeps traces in memory only, so they are lost on restart; fine for a demo.
## Alternatives rejected
Starting a new trace in the poller (loses the link to the request); passing trace ids only in the payload (not understood by tooling); Debezium (see ADR 0013).
