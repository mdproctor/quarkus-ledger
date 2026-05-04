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
 *
 * <p>Carries either a binary verdict ({@code verdict}) for trust scoring, or a continuous
 * quality score ({@code dimensionScore} ∈ [0.0, 1.0]) for dimension-labelled scoring when
 * {@code trustDimension} is set. Both fields may be populated together.
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

    @Column(name = "capability_tag", nullable = false)
    public String capabilityTag = CapabilityTag.GLOBAL;

    /** Application-defined quality dimension label (e.g. {@code "review-thoroughness"}). Null on ordinary attestations. */
    @Column(name = "trust_dimension")
    public String trustDimension;

    /**
     * Continuous quality score in [0.0, 1.0]. Only meaningful when {@code trustDimension} is
     * set. Null on ordinary (binary verdict) attestations.
     */
    @Column(name = "dimension_score")
    public Double dimensionScore;

    @Column(name = "occurred_at", nullable = false)
    public Instant occurredAt;
}
