package io.casehub.ledger.runtime.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.casehub.ledger.api.model.ActorTrustScore.ScoreType;
import io.casehub.ledger.api.model.ActorType;
import io.casehub.ledger.runtime.model.ActorTrustScore;

/** SPI for persisting and querying {@link ActorTrustScore} records. */
public interface ActorTrustScoreRepository {

    /**
     * Find the GLOBAL trust score for an actor, or empty if none computed yet.
     * Backward-compatible shorthand for {@code findByActorIdAndTypeAndKey(actorId, GLOBAL, null)}.
     */
    Optional<ActorTrustScore> findByActorId(String actorId);

    /**
     * Find a scoped trust score for an actor by type and scope key.
     *
     * @param scopeKey null for GLOBAL; capability tag for CAPABILITY; dimension name for DIMENSION
     */
    Optional<ActorTrustScore> findByActorIdAndTypeAndKey(String actorId, ScoreType scoreType, String scopeKey);

    /**
     * Return all trust scores for an actor of a given type.
     * For GLOBAL: returns 0 or 1 result. For CAPABILITY/DIMENSION: returns all scoped rows.
     */
    List<ActorTrustScore> findByActorIdAndScoreType(String actorId, ScoreType scoreType);

    /**
     * Upsert (insert or update) a trust score for the given actor and scope.
     *
     * @param scoreType the score type (GLOBAL, CAPABILITY, DIMENSION)
     * @param scopeKey null for GLOBAL; capability tag or dimension name otherwise
     */
    void upsert(String actorId, ScoreType scoreType, String scopeKey,
            ActorType actorType, double trustScore,
            int decisionCount, int overturnedCount, double alpha, double beta,
            int attestationPositive, int attestationNegative,
            Instant lastComputedAt);

    /**
     * Update the EigenTrust global trust score for an actor's GLOBAL row.
     */
    void updateGlobalTrustScore(String actorId, double globalTrustScore);

    /**
     * Return all computed trust scores across all actors and score types.
     */
    List<ActorTrustScore> findAll();
}
