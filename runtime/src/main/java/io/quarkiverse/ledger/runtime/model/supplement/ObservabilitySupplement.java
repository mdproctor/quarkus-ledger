package io.quarkiverse.ledger.runtime.model.supplement;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Supplement carrying distributed tracing and causality fields.
 *
 * <pre>{@code
 * ObservabilitySupplement os = new ObservabilitySupplement();
 * os.correlationId = Span.current().getSpanContext().getTraceId();
 * os.causedByEntryId = parentEntry.id;
 * entry.attach(os);
 * }</pre>
 */
@Entity
@Table(name = "ledger_supplement_observability")
@DiscriminatorValue("OBSERVABILITY")
public class ObservabilitySupplement extends LedgerSupplement {

    /**
     * OpenTelemetry trace ID linking this entry to a distributed trace.
     * Use the W3C trace context format (32-char hex string).
     */
    @Column(name = "correlation_id", length = 255)
    public String correlationId;

    /**
     * FK to the {@link io.quarkiverse.ledger.runtime.model.LedgerEntry} that
     * causally produced this entry. Null for entries with no known causal predecessor.
     *
     * <p>
     * Use this when an orchestrator (e.g. Claudony) triggers work in Tarkus which
     * triggers a message in Qhorus — the Qhorus entry's {@code causedByEntryId}
     * points to the Tarkus entry, enabling full cross-system causal chain reconstruction.
     */
    @Column(name = "caused_by_entry_id")
    public UUID causedByEntryId;
}
