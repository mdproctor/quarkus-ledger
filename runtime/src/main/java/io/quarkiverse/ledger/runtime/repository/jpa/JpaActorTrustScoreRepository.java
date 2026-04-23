package io.quarkiverse.ledger.runtime.repository.jpa;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import io.quarkiverse.ledger.runtime.model.ActorTrustScore;
import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.repository.ActorTrustScoreRepository;
import io.quarkus.hibernate.orm.PersistenceUnit;

/**
 * JPA / EntityManager implementation of {@link ActorTrustScoreRepository}.
 *
 * <p>
 * Upsert is implemented as a find-then-update to remain compatible with both H2 (dev/test)
 * and PostgreSQL (production) without requiring database-specific SQL syntax.
 */
@ApplicationScoped
public class JpaActorTrustScoreRepository implements ActorTrustScoreRepository {

    @Inject
    @PersistenceUnit("qhorus")
    EntityManager em;

    /** {@inheritDoc} */
    @Override
    public Optional<ActorTrustScore> findByActorId(final String actorId) {
        return Optional.ofNullable(em.find(ActorTrustScore.class, actorId));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void upsert(final String actorId, final ActorType actorType, final double trustScore,
            final int decisionCount, final int overturnedCount,
            final double alpha, final double beta,
            final int attestationPositive, final int attestationNegative,
            final Instant lastComputedAt) {

        ActorTrustScore score = em.find(ActorTrustScore.class, actorId);
        if (score == null) {
            score = new ActorTrustScore();
            score.actorId = actorId;
        }
        score.actorType = actorType;
        score.trustScore = trustScore;
        score.alpha = alpha;
        score.beta = beta;
        score.decisionCount = decisionCount;
        score.overturnedCount = overturnedCount;
        score.attestationPositive = attestationPositive;
        score.attestationNegative = attestationNegative;
        score.lastComputedAt = lastComputedAt;
        em.merge(score);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void updateGlobalTrustScore(final String actorId, final double globalTrustScore) {
        final ActorTrustScore score = em.find(ActorTrustScore.class, actorId);
        if (score != null) {
            score.globalTrustScore = globalTrustScore;
            em.merge(score);
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<ActorTrustScore> findAll() {
        return em.createNamedQuery("ActorTrustScore.findAll", ActorTrustScore.class)
                .getResultList();
    }
}
