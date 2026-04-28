package io.quarkiverse.ledger.runtime.service;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkiverse.ledger.runtime.repository.ActorTrustScoreRepository;

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
 * Phase 1: both overloads query the global trust score.
 * Phase 2 (after Group B — #61): the capability overload will query the per-capability score
 * and fall back to global when no capability-specific score exists.
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
     * Phase 1: falls back to the global score (capability-scoped scores require Group B — #61).
     * Phase 2: will query {@code ScoreType.CAPABILITY} for {@code capabilityTag} before
     * falling back to global.
     *
     * @param actorId the actor identity string
     * @param capabilityTag the capability tag (e.g. {@code "security-review"})
     * @param minTrust minimum trust score in [0.0, 1.0]
     * @return {@code true} if the actor meets the threshold for this capability
     */
    public boolean meetsThreshold(final String actorId, final String capabilityTag,
            final double minTrust) {
        // TODO #61: query ScoreType.CAPABILITY for capabilityTag before falling back
        return meetsThreshold(actorId, minTrust);
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
}
