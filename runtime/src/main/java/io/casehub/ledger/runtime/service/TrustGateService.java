package io.casehub.ledger.runtime.service;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.ledger.api.model.ActorTrustScore.ScoreType;
import io.casehub.ledger.runtime.model.ActorTrustScore;
import io.casehub.ledger.runtime.repository.ActorTrustScoreRepository;

/**
 * CDI bean for trust threshold enforcement.
 *
 * <p>
 * Provides a single query point for checking whether an actor meets a minimum trust
 * threshold before a work assignment is made. Consumers call
 * {@link #meetsThreshold(String, double)} rather than querying {@link ActorTrustScoreRepository}
 * directly — this ensures threshold logic stays in one place.
 *
 * <p>
 * The capability overload queries the CAPABILITY score first and falls back to the global
 * score when no capability-specific score has been computed yet (#61).
 */
@ApplicationScoped
public class TrustGateService {

    private final ActorTrustScoreRepository repository;

    @Inject
    public TrustGateService(final ActorTrustScoreRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns {@code true} if the actor's global trust score meets or exceeds {@code minTrust}.
     * Returns {@code false} if the actor has no computed score — not-yet-evaluated actors are
     * not trusted by default.
     *
     * @param actorId the actor identity string
     * @param minTrust minimum trust score in [0.0, 1.0]
     * @return {@code true} if the actor meets the threshold
     */
    public boolean meetsThreshold(final String actorId, final double minTrust) {
        return repository.findByActorId(actorId)
                .map(s -> s.trustScore >= minTrust)
                .orElse(false);
    }

    /**
     * Returns {@code true} if the actor's trust score for the given capability meets
     * {@code minTrust}.
     *
     * <p>
     * Queries the {@code CAPABILITY} score for {@code capabilityTag} first; falls back to the
     * global score when no capability-specific score has been computed yet.
     *
     * @param actorId the actor identity string
     * @param capabilityTag the capability tag (e.g. {@code "security-review"})
     * @param minTrust minimum trust score in [0.0, 1.0]
     * @return {@code true} if the actor meets the threshold for this capability
     */
    public boolean meetsThreshold(final String actorId, final String capabilityTag,
            final double minTrust) {
        return repository
                .findByActorIdAndTypeAndKey(actorId, ScoreType.CAPABILITY, capabilityTag)
                .map(s -> s.trustScore >= minTrust)
                .orElseGet(() -> meetsThreshold(actorId, minTrust));
    }

    /**
     * Returns the actor's current global trust score, or {@link Optional#empty()} if no score
     * has been computed yet.
     *
     * @param actorId the actor identity string
     * @return the trust score in [0.0, 1.0], or empty
     */
    public Optional<Double> currentScore(final String actorId) {
        return repository.findByActorId(actorId).map(s -> s.trustScore);
    }

    /**
     * Returns the actor's trust score for the given capability, or {@link Optional#empty()} if
     * no capability-specific score has been computed yet.
     *
     * @param actorId the actor identity string
     * @param capabilityTag the capability tag (e.g. {@code "security-review"})
     * @return the capability trust score in [0.0, 1.0], or empty
     */
    public Optional<Double> currentScore(final String actorId, final String capabilityTag) {
        return repository
                .findByActorIdAndTypeAndKey(actorId, ScoreType.CAPABILITY, capabilityTag)
                .map(s -> s.trustScore);
    }

    /**
     * Returns all dimension trust scores for the given actor, keyed by dimension name.
     * Returns an empty map if no dimension scores have been computed yet.
     *
     * @param actorId the actor identity string
     * @return dimension name → score in [0.0, 1.0] for each DIMENSION row
     */
    public Map<String, Double> dimensionScores(final String actorId) {
        return repository.findByActorIdAndScoreType(actorId, ScoreType.DIMENSION).stream()
                .collect(Collectors.toMap(s -> s.scopeKey, s -> s.trustScore));
    }

    /**
     * Returns the trust score for a specific dimension, or empty if not yet computed.
     *
     * @param actorId the actor identity string
     * @param dimension the dimension name (e.g. {@code "review-thoroughness"})
     * @return the dimension score in [0.0, 1.0], or empty
     */
    public Optional<Double> dimensionScore(final String actorId, final String dimension) {
        return repository.findByActorIdAndTypeAndKey(actorId, ScoreType.DIMENSION, dimension)
                .map(s -> s.trustScore);
    }

    /**
     * Returns the full {@link ActorTrustScore} entity for the given actor, or empty if no score
     * has been computed yet. Use this when the caller needs metrics beyond the scalar score
     * (e.g. decisionCount, overturnedCount, attestation counts).
     *
     * @param actorId the actor identity string
     * @return the full score entity, or empty
     */
    public Optional<ActorTrustScore> findScore(final String actorId) {
        return repository.findByActorId(actorId);
    }
}
