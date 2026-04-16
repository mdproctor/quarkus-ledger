package io.quarkiverse.ledger.runtime.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import io.quarkiverse.ledger.runtime.model.supplement.ComplianceSupplement;
import io.quarkiverse.ledger.runtime.model.supplement.LedgerSupplement;
import io.quarkiverse.ledger.runtime.model.supplement.LedgerSupplementSerializer;
import io.quarkiverse.ledger.runtime.model.supplement.ObservabilitySupplement;
import io.quarkiverse.ledger.runtime.model.supplement.ProvenanceSupplement;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Abstract base for all ledger entries.
 *
 * <h2>Core fields</h2>
 * <p>
 * {@code LedgerEntry} holds exactly the fields that are relevant for every entry,
 * every consumer, every time: the subject aggregate, sequence position, actor identity,
 * timestamp, and the tamper-evident hash chain. Nothing else.
 *
 * <h2>Supplements</h2>
 * <p>
 * Optional cross-cutting concerns are handled by
 * {@link io.quarkiverse.ledger.runtime.model.supplement.LedgerSupplement} subclasses
 * attached via {@link #attach(LedgerSupplement)}:
 * <ul>
 * <li>{@link ComplianceSupplement} — GDPR Art.22 decision snapshot, governance</li>
 * <li>{@link ProvenanceSupplement} — workflow source entity</li>
 * <li>{@link ObservabilitySupplement} — OTel tracing, causality</li>
 * </ul>
 * If a consumer never calls {@code attach()}, no supplement tables are written
 * and the lazy {@code supplements} list is never initialised — zero overhead.
 *
 * <h2>JPA JOINED inheritance</h2>
 * <p>
 * Domain-specific subclasses (e.g. {@code WorkItemLedgerEntry} in Tarkus) extend
 * this class and add a sibling table joined on {@code id}. Supplements are orthogonal
 * to subclasses — any subclass can attach any supplement.
 *
 * <h2>Hash chain</h2>
 * <p>
 * The canonical form for SHA-256 chaining uses only the six core fields:
 * {@code subjectId|seqNum|entryType|actorId|actorRole|occurredAt}.
 * Supplement fields are deliberately excluded — they are enrichment, not tamper-evidence
 * targets. Subclass-specific fields are also excluded.
 */
@Entity
@Inheritance(strategy = InheritanceType.JOINED)
@DiscriminatorColumn(name = "dtype", discriminatorType = DiscriminatorType.STRING)
@Table(name = "ledger_entry")
public abstract class LedgerEntry extends PanacheEntityBase {

    // ── Core identity ─────────────────────────────────────────────────────────

    /** Primary key — UUID assigned on first persist. */
    @Id
    public UUID id;

    /**
     * The aggregate this entry belongs to — the domain object whose lifecycle
     * is being recorded. Scopes the sequence number and hash chain.
     */
    @Column(name = "subject_id", nullable = false)
    public UUID subjectId;

    /** Position of this entry in the per-subject ledger sequence (1-based). */
    @Column(name = "sequence_number", nullable = false)
    public int sequenceNumber;

    /** Whether this entry is a command (intent), event (fact), or attestation record. */
    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    public LedgerEntryType entryType;

    // ── Actor ─────────────────────────────────────────────────────────────────

    /** Identity of the actor who triggered this transition. */
    @Column(name = "actor_id")
    public String actorId;

    /** Whether the actor is a human, autonomous agent, or the system itself. */
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type")
    public ActorType actorType;

    /** The functional role of the actor in this transition — e.g. {@code "Resolver"}. */
    @Column(name = "actor_role")
    public String actorRole;

    // ── Timing ────────────────────────────────────────────────────────────────

    /** When this entry was recorded — set automatically on first persist. */
    @Column(name = "occurred_at", nullable = false)
    public Instant occurredAt;

    // ── Hash chain ────────────────────────────────────────────────────────────

    /**
     * SHA-256 digest of the previous entry for this subject.
     * {@code null} for the first entry (no previous entry exists).
     */
    @Column(name = "previous_hash")
    public String previousHash;

    /**
     * SHA-256 digest of this entry's canonical content chained from {@code previousHash}.
     * Null when hash chain is disabled ({@code quarkus.ledger.hash-chain.enabled=false}).
     */
    public String digest;

    // ── Supplements ───────────────────────────────────────────────────────────

    /**
     * Lazily-loaded supplements attached to this entry.
     * Never initialised unless a supplement is attached or explicitly accessed.
     * Use {@link #attach(LedgerSupplement)}, {@link #compliance()},
     * {@link #provenance()}, and {@link #observability()} for type-safe access.
     */
    @OneToMany(mappedBy = "ledgerEntry", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    public List<LedgerSupplement> supplements = new ArrayList<>();

    /**
     * Denormalised JSON snapshot of all attached supplements.
     * Written automatically by {@link #attach(LedgerSupplement)}.
     * Enables fast single-entry reads without joining supplement tables.
     * Format: {@code {"COMPLIANCE":{...},"OBSERVABILITY":{...}}}.
     */
    @Column(name = "supplement_json", columnDefinition = "TEXT")
    public String supplementJson;

    // ── Supplement helpers ────────────────────────────────────────────────────

    /**
     * Attach a supplement to this entry, replacing any existing supplement of the
     * same type. Also refreshes {@link #supplementJson} to keep it in sync.
     *
     * @param supplement the supplement to attach; must not be null
     */
    public void attach(final LedgerSupplement supplement) {
        supplement.ledgerEntry = this;
        supplements.removeIf(s -> s.getClass() == supplement.getClass());
        supplements.add(supplement);
        supplementJson = LedgerSupplementSerializer.toJson(supplements);
    }

    /**
     * Returns the {@link ComplianceSupplement} attached to this entry, if any.
     *
     * @return the compliance supplement, or empty if none is attached
     */
    public Optional<ComplianceSupplement> compliance() {
        return supplements.stream()
                .filter(ComplianceSupplement.class::isInstance)
                .map(ComplianceSupplement.class::cast)
                .findFirst();
    }

    /**
     * Returns the {@link ProvenanceSupplement} attached to this entry, if any.
     *
     * @return the provenance supplement, or empty if none is attached
     */
    public Optional<ProvenanceSupplement> provenance() {
        return supplements.stream()
                .filter(ProvenanceSupplement.class::isInstance)
                .map(ProvenanceSupplement.class::cast)
                .findFirst();
    }

    /**
     * Returns the {@link ObservabilitySupplement} attached to this entry, if any.
     *
     * @return the observability supplement, or empty if none is attached
     */
    public Optional<ObservabilitySupplement> observability() {
        return supplements.stream()
                .filter(ObservabilitySupplement.class::isInstance)
                .map(ObservabilitySupplement.class::cast)
                .findFirst();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Assigns a UUID primary key and sets {@code occurredAt} before the entity is inserted. */
    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }
}
