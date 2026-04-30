package io.casehub.ledger.runtime.service;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import io.quarkus.arc.DefaultBean;

/**
 * Populates {@code traceId} from the active OpenTelemetry span context.
 *
 * <p>
 * Safe to call when no OTel SDK is configured — {@code Span.current()} returns
 * the no-op span, whose {@code SpanContext.isValid()} is {@code false}, so
 * {@link #currentTraceId()} returns empty and {@code traceId} stays null.
 *
 * <p>
 * Override this bean to integrate with a different tracing system.
 */
@DefaultBean
@ApplicationScoped
public class OtelTraceIdProvider implements LedgerTraceIdProvider {

    @Override
    public Optional<String> currentTraceId() {
        SpanContext ctx = Span.current().getSpanContext();
        return ctx.isValid() ? Optional.of(ctx.getTraceId()) : Optional.empty();
    }
}
