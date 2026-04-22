package io.quarkiverse.ledger.runtime.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.PrePersist;

import io.quarkiverse.ledger.runtime.model.LedgerEntry;

/**
 * JPA entity listener that auto-populates {@link LedgerEntry#traceId} from the
 * active distributed trace context at persist time.
 *
 * <p>
 * Only fires when {@code traceId} is null — callers that set it explicitly are unaffected.
 * Registered via {@code @EntityListeners} on {@link LedgerEntry} and inherited by all subclasses.
 */
@ApplicationScoped
public class LedgerTraceListener {

    @Inject
    LedgerTraceIdProvider traceIdProvider;

    @PrePersist
    public void prePersist(Object entity) {
        if (!(entity instanceof LedgerEntry entry))
            return;
        if (entry.traceId != null)
            return;
        traceIdProvider.currentTraceId().ifPresent(id -> entry.traceId = id);
    }
}
