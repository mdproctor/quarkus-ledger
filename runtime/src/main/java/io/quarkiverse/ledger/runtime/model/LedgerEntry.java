package io.quarkiverse.ledger.runtime.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Abstract base for all ledger entries.
 *
 * <p>
 * Uses JPA JOINED inheritance — the {@code ledger_entry} table holds all common fields.
 * Domain-specific subclasses (e.g. {@code WorkItemLedgerEntry} in Tarkus,
 * {@code AgentMessageLedgerEntry} in Qhorus) extend this class and add a sibling table
 * joined on {@code id}.
 *
 * <p>
 * The {@code subjectId} field is the aggregate identifier — the domain object this entry
 * belongs to (e.g. a WorkItem UUID, a Channel UUID). Sequence numbering and hash chaining
 * are scoped per subject.
 *
 * <p>
 * The {@code decisionContext} field carries a JSON snapshot of observable state at the moment
 * of the transition — required by GDPR Article 22 and EU AI Act Article 12 for point-in-time
 * auditability.
 */
@Entity
@Inheritance(strategy = InheritanceType.JOINED)
@DiscriminatorColumn(name = "dtype", discriminatorType = DiscriminatorType.STRING)
@Table(name = "ledger_entry")
public abstract class LedgerEntry extends PanacheEntityBase {

    /** Primary key — UUID assigned on first persist. */
    @Id
    public UUID id;

    /**
     * The aggregate this entry belongs to — the domain object whose lifecycle is being recorded.
     * Scopes the sequence number and hash chain. Set by the domain-specific subclass on creation.
     */
    @Column(name = "subject_id", nullable = false)
    public UUID subjectId;

    /** Position of this entry in the per-subject ledger sequence (1-based). */
    @Column(name = "sequence_number", nullable = false)
    public int sequenceNumber;

    /** Whether this entry is a command (intent), an event (fact), or an attestation record. */
    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    public LedgerEntryType entryType;

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

    /**
     * Reference to the policy or procedure version that governed this action.
     * Null when the actor did not supply a plan reference.
     */
    @Column(name = "plan_ref")
    public String planRef;

    /** The actor's stated basis for the decision. Null when not provided. */
    @Column(columnDefinition = "TEXT")
    public String rationale;

    /**
     * JSON snapshot of observable state at the moment of this transition.
     * Content is domain-specific; populated when decision-context capture is enabled.
     * Addresses GDPR Article 22 and EU AI Act Article 12 explainability requirements.
     */
    @Column(name = "decision_context", columnDefinition = "TEXT")
    public String decisionContext;

    /** Structured evidence supplied by the actor. Null unless evidence capture is enabled. */
    @Column(columnDefinition = "TEXT")
    public String evidence;

    /** Optional free-text or JSON detail — delegation targets, rejection reasons, etc. */
    @Column(columnDefinition = "TEXT")
    public String detail;

    /** FK to the ledger entry that causally produced this entry. Null for direct transitions. */
    @Column(name = "caused_by_entry_id")
    public UUID causedByEntryId;

    /** OpenTelemetry trace ID linking this entry to a distributed trace. */
    @Column(name = "correlation_id")
    public String correlationId;

    /** Identifier of the external entity that originated this subject (e.g. a workflow instance). */
    @Column(name = "source_entity_id")
    public String sourceEntityId;

    /** Type of the external entity — e.g. {@code "Flow:WorkflowInstance"}. */
    @Column(name = "source_entity_type")
    public String sourceEntityType;

    /** The system that owns the external entity — e.g. {@code "quarkus-flow"}. */
    @Column(name = "source_entity_system")
    public String sourceEntitySystem;

    /**
     * SHA-256 digest of the previous entry for this subject.
     * {@code "GENESIS"} for the first entry. Null when hash chain is disabled.
     */
    @Column(name = "previous_hash")
    public String previousHash;

    /**
     * SHA-256 digest of this entry's canonical content chained from {@code previousHash}.
     * Null when hash chain is disabled ({@code quarkus.ledger.hash-chain.enabled=false}).
     */
    public String digest;

    /** When this entry was recorded — set automatically on first persist. */
    @Column(name = "occurred_at", nullable = false)
    public Instant occurredAt;

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
