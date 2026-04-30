package io.casehub.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import io.casehub.ledger.api.model.ActorType;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.runtime.model.LedgerEntry;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;
import io.casehub.ledger.runtime.service.LedgerEntryEnricher;
import io.casehub.ledger.service.supplement.TestEntry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

/**
 * Verifies the LedgerEntryEnricher pipeline properties: non-fatal failure and full execution.
 */
@QuarkusTest
@TestProfile(LedgerEnricherPipelineIT.PipelineTestProfile.class)
class LedgerEnricherPipelineIT {

    public static class PipelineTestProfile implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "enricher-pipeline-test";
        }
    }

    /** Always throws — used to verify the pipeline is non-fatal. */
    @ApplicationScoped
    static class ThrowingEnricher implements LedgerEntryEnricher {
        @Override
        public void enrich(final LedgerEntry entry) {
            throw new RuntimeException("simulated enricher failure");
        }
    }

    /** Counts invocations — used to verify all enrichers run. */
    @ApplicationScoped
    static class CountingEnricher implements LedgerEntryEnricher {
        static final AtomicInteger count = new AtomicInteger(0);

        @Override
        public void enrich(final LedgerEntry entry) {
            count.incrementAndGet();
        }
    }

    @Inject
    LedgerEntryRepository repo;

    @BeforeEach
    void reset() {
        CountingEnricher.count.set(0);
    }

    @Test
    @Transactional
    void throwingEnricher_doesNotPreventPersist() {
        final TestEntry entry = buildEntry();

        // Must not throw even though ThrowingEnricher is registered
        repo.save(entry);

        assertThat(repo.findEntryById(entry.id)).isPresent();
    }

    @Test
    @Transactional
    void allEnrichersRun_despiteFailingEnricher() {
        final TestEntry entry = buildEntry();

        repo.save(entry);

        // CountingEnricher must have run (pipeline did not short-circuit on ThrowingEnricher)
        assertThat(CountingEnricher.count.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Transactional
    void traceId_stillPopulated_despiteThrowingEnricher() {
        // TraceIdEnricher must still run even when ThrowingEnricher is registered.
        // Provide an active OTel span so TraceIdEnricher has a real trace ID to populate.
        final TestEntry entry = buildEntry();
        final SpanContext ctx = SpanContext.create(
                "4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7",
                TraceFlags.getSampled(), TraceState.getDefault());

        try (Scope ignored = Span.wrap(ctx).makeCurrent()) {
            repo.save(entry);
        }

        assertThat(entry.traceId).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    private static TestEntry buildEntry() {
        final TestEntry e = new TestEntry();
        e.subjectId = UUID.randomUUID();
        e.sequenceNumber = 1;
        e.entryType = LedgerEntryType.EVENT;
        e.actorId = "pipeline-test-actor";
        e.actorType = ActorType.AGENT;
        e.occurredAt = Instant.now();
        return e;
    }
}
