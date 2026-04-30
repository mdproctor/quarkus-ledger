package io.casehub.ledger.api.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

/**
 * Bayesian Beta trust score for a decision-making actor, scoped by score type.
 *
 * <p>
 * One row per {@code (actor_id, score_type, scope_key)} triple:
 * <ul>
 * <li>{@code GLOBAL} — one row per actor; classic score across all decisions. {@code scope_key} is null.</li>
 * <li>{@code CAPABILITY} — one row per (actor, capability tag); requires #61.</li>
 * <li>{@code DIMENSION} — one row per (actor, trust dimension); requires #62.</li>
 * </ul>
 */
@MappedSuperclass
public class ActorTrustScore {

    /** Score type discriminator — determines what scope_key means. */
    public enum ScoreType {
        /** Classic cross-decision score. scope_key is null. */
        GLOBAL,
        /** Capability-scoped score. scope_key is the capability tag (e.g. "security-review"). Requires #61. */
        CAPABILITY,
        /** Dimension-scoped score. scope_key is the dimension name (e.g. "thoroughness"). Requires #62. */
        DIMENSION
    }

    @Id
    @Column(name = "id", nullable = false)
    public UUID id;

    @Column(name = "actor_id", nullable = false)
    public String actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "score_type", nullable = false)
    public ScoreType scoreType = ScoreType.GLOBAL;

    /** Null for GLOBAL rows; capability tag for CAPABILITY; dimension name for DIMENSION. */
    @Column(name = "scope_key")
    public String scopeKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type")
    public ActorType actorType;

    @Column(name = "trust_score")
    public double trustScore;

    @Column(name = "alpha_value")
    public double alpha;

    @Column(name = "beta_value")
    public double beta;

    @Column(name = "decision_count")
    public int decisionCount;

    @Column(name = "overturned_count")
    public int overturnedCount;

    @Column(name = "attestation_positive")
    public int attestationPositive;

    @Column(name = "attestation_negative")
    public int attestationNegative;

    @Column(name = "last_computed_at")
    public Instant lastComputedAt;

    /**
     * EigenTrust global trust share in [0.0, 1.0]; values sum to ≤ 1.0 across all actors.
     * Only meaningful on GLOBAL rows. Zero when EigenTrust is disabled or not yet computed.
     */
    @Column(name = "global_trust_score")
    public double globalTrustScore;
}
