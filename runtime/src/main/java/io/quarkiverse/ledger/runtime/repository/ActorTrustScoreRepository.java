package io.quarkiverse.ledger.runtime.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.quarkiverse.ledger.runtime.model.ActorTrustScore;
import io.quarkiverse.ledger.runtime.model.ActorType;

/** SPI for persisting and querying {@link ActorTrustScore} records. */
public interface ActorTrustScoreRepository {

    /**
     * Find the trust score for an actor, or empty if none computed yet.
     *
     * @param actorId the actor's identity string
     * @return the computed score, or empty if not yet computed
     */
    Optional<ActorTrustScore> findByActorId(String actorId);

    /**
     * Upsert (insert or update) a trust score for the given actor.
     *
     * @param actorId the actor's identity string
     * @param actorType the type of actor
     * @param trustScore the computed trust score in [0.0, 1.0]
     * @param decisionCount total number of EVENT entries attributed to this actor
     * @param overturnedCount number of decisions with at least one negative attestation
     * @param alpha final α value from the Beta distribution
     * @param beta final β value from the Beta distribution
     * @param attestationPositive total positive attestation count
     * @param attestationNegative total negative attestation count
     * @param lastComputedAt the timestamp of this computation
     */
    void upsert(String actorId, ActorType actorType, double trustScore,
            int decisionCount, int overturnedCount, double alpha, double beta,
            int attestationPositive, int attestationNegative,
            Instant lastComputedAt);

    /**
     * Update the EigenTrust global trust score for an actor. Called after power iteration
     * completes, in a separate pass from the Bayesian Beta {@link #upsert}.
     *
     * @param actorId the actor's identity string
     * @param globalTrustScore the EigenTrust eigenvector component for this actor, in [0.0, 1.0]
     */
    void updateGlobalTrustScore(String actorId, double globalTrustScore);

    /**
     * Return all computed trust scores.
     *
     * @return list of all actor trust scores; empty if none computed yet
     */
    List<ActorTrustScore> findAll();
}
