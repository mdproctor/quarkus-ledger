package io.quarkiverse.ledger.runtime.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkiverse.ledger.runtime.model.LedgerEntry;

/**
 * Enricher that auto-populates {@link LedgerEntry#traceId} from the active OTel span.
 * Extracted from {@code LedgerTraceListener} — same behaviour, now as a pipeline participant.
 */
@ApplicationScoped
public class TraceIdEnricher implements LedgerEntryEnricher {

    private final LedgerTraceIdProvider traceIdProvider;

    @Inject
    public TraceIdEnricher(final LedgerTraceIdProvider traceIdProvider) {
        this.traceIdProvider = traceIdProvider;
    }

    @Override
    public void enrich(final LedgerEntry entry) {
        if (entry.traceId != null) {
            return;
        }
        traceIdProvider.currentTraceId().ifPresent(id -> entry.traceId = id);
    }
}
