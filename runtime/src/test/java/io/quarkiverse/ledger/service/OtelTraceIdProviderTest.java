package io.quarkiverse.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import io.quarkiverse.ledger.runtime.service.OtelTraceIdProvider;

class OtelTraceIdProviderTest {

    private final OtelTraceIdProvider provider = new OtelTraceIdProvider();

    @Test
    void noActiveSpan_returnsEmpty() {
        assertThat(provider.currentTraceId()).isEmpty();
    }

    @Test
    void activeSpan_returnsTraceId() {
        SpanContext ctx = SpanContext.create(
                "4bf92f3577b34da6a3ce929d0e0e4736",
                "00f067aa0ba902b7",
                TraceFlags.getSampled(),
                TraceState.getDefault());
        try (Scope scope = Span.wrap(ctx).makeCurrent()) {
            assertThat(provider.currentTraceId()).contains("4bf92f3577b34da6a3ce929d0e0e4736");
        }
    }

    @Test
    void invalidSpanContext_returnsEmpty() {
        try (Scope scope = Span.wrap(SpanContext.getInvalid()).makeCurrent()) {
            assertThat(provider.currentTraceId()).isEmpty();
        }
    }
}
