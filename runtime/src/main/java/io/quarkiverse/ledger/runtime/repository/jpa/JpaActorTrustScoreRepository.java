package io.quarkiverse.ledger.runtime.repository.jpa;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.ledger.runtime.model.ActorTrustScore;
import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.repository.ActorTrustScoreRepository;

/**
 * Hibernate ORM / Panache implementation of {@link ActorTrustScoreRepository}.
 *
 * <p>
 * Upsert is implemented as a find-then-update to remain compatible with both H2 (dev/test)
 * and PostgreSQL (production) without requiring database-specific SQL syntax.
 */
@ApplicationScoped
public class JpaActorTrustScoreRepository implements ActorTrustScoreRepository {

    /** {@inheritDoc} */
    @Override
    public Optional<ActorTrustScore> findByActorId(final String actorId) {
        return Optional.ofNullable(ActorTrustScore.findById(actorId));
    }

    /** {@inheritDoc} */
    @Override
    public void upsert(final String actorId, final ActorType actorType, final double trustScore,
            final int decisionCount, final int overturnedCount, final int appealCount,
            final int attestationPositive, final int attestationNegative,
            final Instant lastComputedAt) {

        ActorTrustScore score = ActorTrustScore.findById(actorId);
        if (score == null) {
            score = new ActorTrustScore();
            score.actorId = actorId;
        }
        score.actorType = actorType;
        score.trustScore = trustScore;
        score.decisionCount = decisionCount;
        score.overturnedCount = overturnedCount;
        score.appealCount = appealCount;
        score.attestationPositive = attestationPositive;
        score.attestationNegative = attestationNegative;
        score.lastComputedAt = lastComputedAt;
        score.persist();
    }

    /** {@inheritDoc} */
    @Override
    public List<ActorTrustScore> findAll() {
        return ActorTrustScore.listAll();
    }
}
