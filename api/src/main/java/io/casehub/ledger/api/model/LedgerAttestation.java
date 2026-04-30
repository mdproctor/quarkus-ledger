package io.casehub.ledger.api.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

/**
 * A peer attestation stamped onto a {@link LedgerEntry}.
 */
@MappedSuperclass
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
}
