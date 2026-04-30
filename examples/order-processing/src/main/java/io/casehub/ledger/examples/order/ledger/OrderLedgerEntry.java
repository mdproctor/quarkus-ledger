package io.casehub.ledger.examples.order.ledger;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import io.casehub.ledger.runtime.model.LedgerEntry;

/**
 * Domain-specific ledger entry for the Order aggregate.
 *
 * <p>
 * Extends the base {@link LedgerEntry} via JPA JOINED inheritance.
 * The base table {@code ledger_entry} holds all audit fields; this table
 * adds the Order-specific command/event type names and the order status
 * at the moment of the transition.
 *
 * <p>
 * {@code subjectId} (inherited) = {@code orderId} — sequences and the hash
 * chain are scoped per order.
 */
@Entity
@Table(name = "order_ledger_entry")
@DiscriminatorValue("ORDER")
public class OrderLedgerEntry extends LedgerEntry {

    /** The Order this entry belongs to — mirrors subjectId with explicit typing. */
    @Column(name = "order_id", nullable = false)
    public UUID orderId;

    /** The actor's declared intent — e.g. "PlaceOrder", "ShipOrder". */
    @Column(name = "command_type")
    public String commandType;

    /** The observable fact after execution — e.g. "OrderPlaced", "OrderShipped". */
    @Column(name = "event_type")
    public String eventType;

    /** The order status after this transition (snapshot for quick querying). */
    @Column(name = "order_status")
    public String orderStatus;
}
