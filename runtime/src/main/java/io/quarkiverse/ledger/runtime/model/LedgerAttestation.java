package io.quarkiverse.ledger.runtime.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * A peer attestation stamped onto a {@link LedgerEntry}.
 *
 * <p>
 * Attestations allow other actors — human reviewers, audit agents, or automated compliance
 * checks — to formally record their judgment on a ledger entry. The {@code confidence} field
 * carries a 0.0–1.0 score used as input to EigenTrust reputation computation.
 *
 * <p>
 * {@code subjectId} is denormalized from the parent {@link LedgerEntry} for efficient
 * per-subject queries without joining through the entry.
 */
@Entity
@Table(name = "ledger_attestation")
public class LedgerAttestation extends PanacheEntityBase {

    /** Primary key — UUID assigned on first persist. */
    @Id
    public UUID id;

    /** The ledger entry being attested. */
    @Column(name = "ledger_entry_id", nullable = false)
    public UUID ledgerEntryId;

    /** Denormalized aggregate identifier for efficient per-subject queries. */
    @Column(name = "subject_id", nullable = false)
    public UUID subjectId;

    /** Identity of the actor providing this attestation. */
    @Column(name = "attestor_id", nullable = false)
    public String attestorId;

    /** Whether the attestor is a human, autonomous agent, or the system itself. */
    @Enumerated(EnumType.STRING)
    @Column(name = "attestor_type", nullable = false)
    public ActorType attestorType;

    /** The functional role of the attestor — e.g. {@code "Auditor"}. Nullable. */
    @Column(name = "attestor_role")
    public String attestorRole;

    /** The attestor's formal verdict on the ledger entry. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public AttestationVerdict verdict;

    /** Supporting evidence provided by the attestor. Nullable. */
    @Column(columnDefinition = "TEXT")
    public String evidence;

    /**
     * Confidence level for this attestation, in the range 0.0–1.0.
     * Used as input weight for EigenTrust reputation computation.
     */
    @Column(nullable = false)
    public double confidence;

    /** When this attestation was recorded — set automatically on first persist. */
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
