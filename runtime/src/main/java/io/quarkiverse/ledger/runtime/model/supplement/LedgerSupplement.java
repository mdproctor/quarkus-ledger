package io.quarkiverse.ledger.runtime.model.supplement;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

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
 * <li>{@link ProvenanceSupplement} — workflow source entity tracking</li>
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
@Entity
@Inheritance(strategy = InheritanceType.JOINED)
@DiscriminatorColumn(name = "supplement_type", discriminatorType = DiscriminatorType.STRING)
@Table(name = "ledger_supplement")
public abstract class LedgerSupplement extends PanacheEntityBase {

    /** Primary key — UUID assigned on first persist. */
    @Id
    public UUID id;

    /**
     * The ledger entry this supplement belongs to.
     * Loaded lazily to avoid unnecessary joins when reading the base entry.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ledger_entry_id", nullable = false)
    public LedgerEntry ledgerEntry;

    /**
     * Discriminator column value — managed by JPA, read-only via this field.
     * Use {@code instanceof} checks or {@link LedgerEntry#compliance()} etc.
     * for typed access rather than reading this field directly.
     */
    @Column(name = "supplement_type", insertable = false, updatable = false)
    public String supplementType;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
