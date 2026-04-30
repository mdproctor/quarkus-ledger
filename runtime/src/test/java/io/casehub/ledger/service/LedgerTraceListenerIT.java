package io.casehub.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.Test;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import io.casehub.ledger.api.model.ActorType;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;
import io.casehub.ledger.service.supplement.TestEntry;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Verifies that {@link io.casehub.ledger.runtime.service.LedgerTraceListener} is
 * wired correctly by Hibernate and CDI — i.e. that the {@code @PrePersist} callback
 * actually fires with a live injected {@code LedgerTraceIdProvider}.
 */
@QuarkusTest
class LedgerTraceListenerIT {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";

    @Inject
    LedgerEntryRepository repo;

    @Test
    @Transactional
    void traceId_autoPopulatedFromActiveSpan() {
        final TestEntry entry = buildEntry();
        final SpanContext ctx = activeSpanContext();

        try (Scope ignored = Span.wrap(ctx).makeCurrent()) {
            repo.save(entry);
        }

        assertThat(entry.traceId).isEqualTo(TRACE_ID);
    }

    @Test
    @Transactional
    void traceId_notOverwrittenWhenCallerSetsIt() {
        final TestEntry entry = buildEntry();
        entry.traceId = "caller-supplied-trace";
        final SpanContext ctx = activeSpanContext();

        try (Scope ignored = Span.wrap(ctx).makeCurrent()) {
            repo.save(entry);
        }

        assertThat(entry.traceId).isEqualTo("caller-supplied-trace");
    }

    @Test
    @Transactional
    void traceId_nullWhenNoActiveSpan() {
        final TestEntry entry = buildEntry();

        repo.save(entry);

        assertThat(entry.traceId).isNull();
    }

    private static SpanContext activeSpanContext() {
        return SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault());
    }

    private static TestEntry buildEntry() {
        final TestEntry e = new TestEntry();
        e.subjectId = UUID.randomUUID();
        e.sequenceNumber = 1;
        e.entryType = LedgerEntryType.EVENT;
        e.actorId = "test-actor";
        e.actorType = ActorType.AGENT;
        e.occurredAt = Instant.now();
        return e;
    }
}
