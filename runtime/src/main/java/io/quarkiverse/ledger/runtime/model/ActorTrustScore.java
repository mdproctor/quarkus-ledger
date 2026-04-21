package io.quarkiverse.ledger.runtime.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;

/**
 * Bayesian Beta trust score for a decision-making actor.
 * Plain {@code @Entity} — queries via {@code @NamedQuery} + EntityManager.
 */
@Entity
@Table(name = "actor_trust_score")
@NamedQuery(name = "ActorTrustScore.findAll", query = "SELECT s FROM ActorTrustScore s")
public class ActorTrustScore {

    @Id
    @Column(name = "actor_id")
    public String actorId;

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
     * Zero indicates EigenTrust has not yet been computed or is disabled.
     */
    @Column(name = "global_trust_score")
    public double globalTrustScore;
}
