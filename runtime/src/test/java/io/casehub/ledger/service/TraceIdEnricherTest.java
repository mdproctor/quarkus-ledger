package io.casehub.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import io.casehub.ledger.runtime.service.TraceIdEnricher;
import io.casehub.ledger.service.supplement.TestEntry;

class TraceIdEnricherTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private static TestEntry entry() {
        final TestEntry e = new TestEntry();
        e.id = UUID.randomUUID();
        e.subjectId = UUID.randomUUID();
        e.sequenceNumber = 1;
        e.entryType = LedgerEntryType.EVENT;
        e.actorId = "actor";
        e.actorType = ActorType.AGENT;
        e.occurredAt = Instant.now();
        return e;
    }

    private static LedgerTraceIdProvider providing(final String traceId) {
        return () -> Optional.ofNullable(traceId);
    }

    @Test
    void populatesTraceId_whenNullAndProviderHasTrace() {
        final TraceIdEnricher enricher = new TraceIdEnricher(providing(TRACE_ID));
        final TestEntry entry = entry();

        enricher.enrich(entry);

        assertThat(entry.traceId).isEqualTo(TRACE_ID);
    }

    @Test
    void doesNotOverwrite_whenCallerAlreadySetTraceId() {
        final TraceIdEnricher enricher = new TraceIdEnricher(providing(TRACE_ID));
        final TestEntry entry = entry();
        entry.traceId = "caller-supplied";

        enricher.enrich(entry);

        assertThat(entry.traceId).isEqualTo("caller-supplied");
    }

    @Test
    void leavesTraceIdNull_whenProviderReturnsEmpty() {
        final TraceIdEnricher enricher = new TraceIdEnricher(providing(null));
        final TestEntry entry = entry();

        enricher.enrich(entry);

        assertThat(entry.traceId).as("provider returned empty — traceId must remain null").isNull();
    }
}
