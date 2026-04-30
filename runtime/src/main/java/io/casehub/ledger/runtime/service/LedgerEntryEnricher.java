package io.casehub.ledger.runtime.service;

import io.casehub.ledger.runtime.model.LedgerEntry;

/**
 * SPI for auto-populating fields on {@link LedgerEntry} at persist time.
 *
 * <p>
 * Implementations are CDI beans discovered via {@code @Inject @Any Instance<LedgerEntryEnricher>}
 * and invoked in the {@code @PrePersist} pipeline. Implementations must be idempotent and
 * non-fatal — a thrown exception is logged and swallowed; the persist is never blocked.
 *
 * <p>
 * Register an implementation by creating an {@code @ApplicationScoped} CDI bean that
 * implements this interface. No registration step is required.
 */
public interface LedgerEntryEnricher {

    /**
     * Enrich the given entry before it is persisted.
     * Called once per {@code @PrePersist} event. Must not throw. Implementations must be
     * idempotent — this method may be called more than once on the same entry under retried transactions.
     */
    void enrich(LedgerEntry entry);
}
