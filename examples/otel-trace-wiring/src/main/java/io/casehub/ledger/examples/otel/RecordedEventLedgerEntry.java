package io.casehub.ledger.examples.otel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import io.casehub.ledger.runtime.model.LedgerEntry;

/**
 * Domain-specific ledger entry subclass for the OTel trace wiring example.
 *
 * <p>
 * Extends {@link LedgerEntry} via JPA JOINED inheritance — one row appears in
 * {@code ledger_entry} (base fields including {@code trace_id}) and one row in
 * {@code recorded_event_ledger_entry} (the {@code event_name} column).
 *
 * <p>
 * The {@code traceId} field on the base class is populated automatically by
 * {@link io.casehub.ledger.runtime.service.LedgerTraceListener} at persist time.
 * No code in this class or in the REST resource touches {@code traceId} directly.
 */
@Entity
@Table(name = "recorded_event_ledger_entry")
public class RecordedEventLedgerEntry extends LedgerEntry {

    /** The name of the event being recorded. */
    @Column(name = "event_name")
    public String eventName;
}
