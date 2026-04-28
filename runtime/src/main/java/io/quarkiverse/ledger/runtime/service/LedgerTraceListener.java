package io.quarkiverse.ledger.runtime.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.PrePersist;

import org.jboss.logging.Logger;

import io.quarkiverse.ledger.runtime.model.LedgerEntry;

/**
 * JPA entity listener that runs the {@link LedgerEntryEnricher} pipeline on every
 * {@link LedgerEntry} before it is persisted.
 *
 * <p>
 * Registered via {@code @EntityListeners} on {@link LedgerEntry}. Enrichers are
 * CDI beans discovered via {@code Instance<LedgerEntryEnricher>}. Each enricher runs
 * in an unspecified order; failure is logged and swallowed — the persist is never blocked.
 */
@ApplicationScoped
public class LedgerTraceListener {

    private static final Logger log = Logger.getLogger(LedgerTraceListener.class);

    @Inject
    @Any
    Instance<LedgerEntryEnricher> enrichers;

    @PrePersist
    public void prePersist(final Object entity) {
        if (!(entity instanceof LedgerEntry entry)) {
            return;
        }
        for (final LedgerEntryEnricher enricher : enrichers) {
            try {
                enricher.enrich(entry);
            } catch (final Exception ex) {
                log.warnf("LedgerEntryEnricher %s failed — entry will still be saved: %s",
                        enricher.getClass().getSimpleName(), ex.getMessage());
            }
        }
    }
}
