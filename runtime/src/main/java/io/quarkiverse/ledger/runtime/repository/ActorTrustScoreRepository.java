package io.quarkiverse.ledger.runtime.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.quarkiverse.ledger.runtime.model.ActorTrustScore;
import io.quarkiverse.ledger.runtime.model.ActorType;

/**
 * SPI for persisting and querying {@link ActorTrustScore} records.
 *
 * <p>
 * The default implementation uses Hibernate ORM with Panache. Alternative implementations
 * can be substituted via CDI.
 */
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
     * @param appealCount appeal count (reserved for future use)
     * @param attestationPositive total positive attestation count
     * @param attestationNegative total negative attestation count
     * @param lastComputedAt the timestamp of this computation
     */
    void upsert(String actorId, ActorType actorType, double trustScore,
            int decisionCount, int overturnedCount, int appealCount,
            int attestationPositive, int attestationNegative,
            Instant lastComputedAt);

    /**
     * Return all computed trust scores.
     *
     * @return list of all actor trust scores; empty if none computed yet
     */
    List<ActorTrustScore> findAll();
}
