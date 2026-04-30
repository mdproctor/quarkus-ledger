package io.casehub.ledger.api.model.supplement;

import java.util.UUID;

import io.casehub.ledger.api.model.LedgerEntry;

/**
 * Abstract base for all ledger supplements.
 *
 * <p>
 * A <strong>supplement</strong> is an optional, lazily-loaded extension to a
 * {@link LedgerEntry} that carries a named group of cross-cutting fields. Supplements
 * exist in separate joined tables and are never written unless the consumer explicitly
 * attaches one — consumers that do not use supplements incur zero schema or runtime cost.
 *
 * <p>
 * Two built-in supplements are provided:
 * <ul>
 * <li>{@link ComplianceSupplement} — GDPR Art.22 decision snapshot, EU AI Act Art.12,
 * governance reference, rationale</li>
 * <li>{@link ProvenanceSupplement} — workflow source entity</li>
 * </ul>
 *
 * <p>
 * Supplements are accessed via the typed helper methods on {@link LedgerEntry}:
 * {@code entry.compliance()} and {@code entry.provenance()}.
 * Use {@code entry.attach(supplement)} to add or replace a supplement; this also
 * keeps {@code entry.supplementJson} in sync automatically.
 *
 * <p>
 * <strong>Zero-complexity guarantee:</strong> If a consumer never calls
 * {@code entry.attach()}, no supplement table rows are written and the lazy
 * {@code supplements} list is never initialised.
 */
public abstract class LedgerSupplement {

    /** Primary key — UUID assigned on first persist. */
    public UUID id;

    /**
     * The ledger entry this supplement belongs to.
     */
    public LedgerEntry ledgerEntry;

    /**
     * Discriminator column value — identifies the supplement type.
     * Use {@code instanceof} checks or {@link LedgerEntry#compliance()} etc.
     * for typed access rather than reading this field directly.
     */
    public String supplementType;
}
