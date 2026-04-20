package io.quarkiverse.ledger.runtime.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * A peer attestation stamped onto a {@link LedgerEntry}.
 * Plain {@code @Entity} — queries via {@code @NamedQuery} + EntityManager.
 */
@Entity
@Table(name = "ledger_attestation")
@NamedQuery(name = "LedgerAttestation.findByEntryId", query = "SELECT a FROM LedgerAttestation a WHERE a.ledgerEntryId = :entryId ORDER BY a.occurredAt ASC")
@NamedQuery(name = "LedgerAttestation.findBySubjectId", query = "SELECT a FROM LedgerAttestation a WHERE a.subjectId = :subjectId ORDER BY a.occurredAt ASC")
@NamedQuery(name = "LedgerAttestation.findByEntryIds", query = "SELECT a FROM LedgerAttestation a WHERE a.ledgerEntryId IN :entryIds")
public class LedgerAttestation {

    @Id
    public UUID id;

    @Column(name = "ledger_entry_id", nullable = false)
    public UUID ledgerEntryId;

    @Column(name = "subject_id", nullable = false)
    public UUID subjectId;

    @Column(name = "attestor_id", nullable = false)
    public String attestorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "attestor_type", nullable = false)
    public ActorType attestorType;

    @Column(name = "attestor_role")
    public String attestorRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public AttestationVerdict verdict;

    @Column(columnDefinition = "TEXT")
    public String evidence;

    @Column(nullable = false)
    public double confidence;

    @Column(name = "occurred_at", nullable = false)
    public Instant occurredAt;

    @PrePersist
    void prePersist() {
        if (id == null)
            id = UUID.randomUUID();
        if (occurredAt == null)
            occurredAt = Instant.now();
    }
}
