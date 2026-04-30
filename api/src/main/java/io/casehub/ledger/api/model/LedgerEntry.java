package io.casehub.ledger.api.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import io.casehub.ledger.api.model.supplement.ComplianceSupplement;
import io.casehub.ledger.api.model.supplement.LedgerSupplement;
import io.casehub.ledger.api.model.supplement.LedgerSupplementSerializer;
import io.casehub.ledger.api.model.supplement.ProvenanceSupplement;

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
 * {@link io.casehub.ledger.api.model.supplement.LedgerSupplement} subclasses
 * attached via {@link #attach(LedgerSupplement)}:
 * <ul>
 * <li>{@link ComplianceSupplement} — GDPR Art.22 decision snapshot, governance</li>
 * <li>{@link ProvenanceSupplement} — workflow source entity</li>
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
 * The {@code digest} field holds the RFC 9162 leaf hash — {@code SHA-256(0x00 | canonical fields)}.
 * Chain integrity is maintained by the Merkle Mountain Range in {@code LedgerMerkleFrontier}.
 */
public abstract class LedgerEntry {

    // ── Core identity ─────────────────────────────────────────────────────────

    /** Primary key — UUID assigned on first persist. */
    public UUID id;

    /**
     * The aggregate this entry belongs to — the domain object whose lifecycle
     * is being recorded. Scopes the sequence number and hash chain.
     */
    public UUID subjectId;

    /** Position of this entry in the per-subject ledger sequence (1-based). */
    public int sequenceNumber;

    /** Whether this entry is a command (intent), event (fact), or attestation record. */
    public LedgerEntryType entryType;

    // ── Actor ─────────────────────────────────────────────────────────────────

    /** Identity of the actor who triggered this transition. */
    public String actorId;

    /** Whether the actor is a human, autonomous agent, or the system itself. */
    public ActorType actorType;

    /** The functional role of the actor in this transition — e.g. {@code "Resolver"}. */
    public String actorRole;

    // ── Timing ────────────────────────────────────────────────────────────────

    /** When this entry was recorded — set automatically on first persist. */
    public Instant occurredAt;

    // ── Hash chain ────────────────────────────────────────────────────────────

    /**
     * RFC 9162 leaf hash: {@code SHA-256(0x00 | canonicalFields)}.
     * Null when hash chain is disabled ({@code casehub.ledger.hash-chain.enabled=false}).
     */
    public String digest;

    // ── Observability & causality ─────────────────────────────────────────────

    /**
     * OpenTelemetry trace ID linking this entry to a distributed trace.
     * W3C trace context format (32-char hex string).
     */
    public String traceId;

    /**
     * FK to the ledger entry that causally produced this entry.
     * Null for entries with no known causal predecessor.
     *
     * <p>
     * Enables cross-system causal chain traversal via
     * {@code LedgerEntryRepository#findCausedBy(UUID)}.
     * When Claudony orchestrates Tarkus → Qhorus, each downstream entry's
     * {@code causedByEntryId} points to its upstream cause.
     */
    public UUID causedByEntryId;

    // ── Supplements ───────────────────────────────────────────────────────────

    /**
     * Lazily-loaded supplements attached to this entry.
     * Never initialised unless a supplement is attached or explicitly accessed.
     * Use {@link #attach(LedgerSupplement)}, {@link #compliance()},
     * and {@link #provenance()} for type-safe access.
     */
    public List<LedgerSupplement> supplements = new ArrayList<>();

    /**
     * Denormalised JSON snapshot of all attached supplements.
     * Written automatically by {@link #attach(LedgerSupplement)}.
     * Enables fast single-entry reads without joining supplement tables.
     * Format: {@code {"COMPLIANCE":{...},"PROVENANCE":{...}}}.
     */
    public String supplementJson;

    // ── Supplement helpers ────────────────────────────────────────────────────

    /**
     * Attach a supplement to this entry, replacing any existing supplement of the
     * same type. Also refreshes {@link #supplementJson} to keep it in sync.
     *
     * <p>
     * <strong>Important:</strong> After attaching, do not mutate the supplement's fields
     * directly without calling {@link #refreshSupplementJson()} — direct field mutation
     * leaves {@code supplementJson} stale. Prefer re-attaching a new supplement instance
     * when fields need to change.
     *
     * @param supplement the supplement to attach; must not be null
     */
    public void attach(final LedgerSupplement supplement) {
        Objects.requireNonNull(supplement, "supplement must not be null");
        supplement.ledgerEntry = this;
        supplements.removeIf(s -> s.getClass() == supplement.getClass());
        supplements.add(supplement);
        supplementJson = LedgerSupplementSerializer.toJson(supplements);
    }

    /**
     * Refreshes {@link #supplementJson} from the current state of the
     * {@link #supplements} list.
     *
     * <p>
     * Call this after mutating a supplement's fields in-place (e.g. when adding
     * {@code rationale} to an already-attached {@link ComplianceSupplement}):
     *
     * <pre>{@code
     * entry.compliance().ifPresent(cs -> {
     *     cs.rationale = reason;
     *     entry.refreshSupplementJson();
     * });
     * }</pre>
     */
    public void refreshSupplementJson() {
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
}
