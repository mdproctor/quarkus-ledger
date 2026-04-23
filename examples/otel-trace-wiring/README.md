# OTel Trace Wiring Example

Demonstrates that `LedgerEntry.traceId` is automatically populated from the active
OpenTelemetry span at persist time. Every ledger write that happens inside an HTTP request
gets the request's OTel trace ID recorded against it — no code changes required at the
write site.

## How it works

`LedgerTraceListener` is a JPA `@EntityListeners` callback registered on the abstract
`LedgerEntry` base class. At `@PrePersist` time it calls `OtelTraceIdProvider.currentTraceId()`,
which reads `Span.current().getSpanContext().getTraceId()`. Quarkus OTel automatically creates
a server span for every inbound HTTP request, so any persist that happens inside a request
handler sees a valid span and `traceId` is populated.

**No call-site code is needed.** Writing a ledger entry looks exactly the same with or
without OTel on the classpath:

```java
entry.subjectId  = UUID.randomUUID();
entry.entryType  = LedgerEntryType.EVENT;
entry.actorId    = request.actorId();
// ... other fields ...
repo.save(entry);
// entry.traceId is now set — LedgerTraceListener did it
```

## Verifying it works

After a successful POST to `/events`, the response includes `traceId`:

```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

This is the W3C trace ID (32 hex chars) that identifies the distributed trace in your
observability platform. Search for this ID in your tracing backend (Jaeger, Tempo, Honeycomb,
etc.) to see the full request trace alongside the ledger entry that was written during it.

## Running the example

```bash
# Run tests
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -f examples/otel-trace-wiring/pom.xml

# Start in dev mode (exports to localhost:4317 — adjust endpoint as needed)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn quarkus:dev -f examples/otel-trace-wiring/pom.xml
```

## Configuration

The key OTel settings in `application.properties`:

```properties
quarkus.otel.sdk.disabled=false
quarkus.otel.traces.sampler=always_on
quarkus.otel.exporter.otlp.traces.endpoint=http://localhost:4317
```

In production, point `quarkus.otel.exporter.otlp.traces.endpoint` at your collector.
The `traceId` field will be populated regardless of whether export succeeds — it comes
from the active span context, not from the exporter.
