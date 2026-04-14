package io.quarkiverse.ledger.runtime.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Stores the computed EigenTrust-inspired trust score for a decision-making actor.
 *
 * <p>
 * Scores are recomputed nightly by {@link io.quarkiverse.ledger.runtime.service.TrustScoreJob}
 * from the full ledger history. A score of 0.5 is the neutral prior; scores above 0.5 indicate
 * a reliable actor history; scores below 0.5 indicate a history of contested or overturned
 * decisions.
 *
 * <p>
 * {@code actorId} is the primary key — one row per actor, upserted on each nightly run.
 */
@Entity
@Table(name = "actor_trust_score")
public class ActorTrustScore extends PanacheEntityBase {

    /** Primary key — the actor's identity string. */
    @Id
    @Column(name = "actor_id")
    public String actorId;

    /** Whether this actor is a human, autonomous agent, or the system itself. */
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type")
    public ActorType actorType;

    /** Computed trust score in the range [0.0, 1.0]. Neutral prior is 0.5. */
    @Column(name = "trust_score")
    public double trustScore;

    /** Total number of EVENT-type ledger entries attributed to this actor. */
    @Column(name = "decision_count")
    public int decisionCount;

    /** Number of decisions that received at least one negative attestation. */
    @Column(name = "overturned_count")
    public int overturnedCount;

    /** Number of appeal events attributed to this actor (reserved for future use). */
    @Column(name = "appeal_count")
    public int appealCount;

    /** Total count of positive attestations (SOUND or ENDORSED) across all decisions. */
    @Column(name = "attestation_positive")
    public int attestationPositive;

    /** Total count of negative attestations (FLAGGED or CHALLENGED) across all decisions. */
    @Column(name = "attestation_negative")
    public int attestationNegative;

    /** When this score was last computed. */
    @Column(name = "last_computed_at")
    public Instant lastComputedAt;
}
