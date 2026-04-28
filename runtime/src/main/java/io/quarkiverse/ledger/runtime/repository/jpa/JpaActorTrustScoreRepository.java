package io.quarkiverse.ledger.runtime.repository.jpa;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import io.quarkiverse.ledger.runtime.model.ActorTrustScore;
import io.quarkiverse.ledger.runtime.model.ActorTrustScore.ScoreType;
import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.persistence.LedgerPersistenceUnit;
import io.quarkiverse.ledger.runtime.repository.ActorTrustScoreRepository;

/**
 * JPA / EntityManager implementation of {@link ActorTrustScoreRepository}.
 *
 * <p>
 * Upsert is a find-then-update to remain compatible with H2 and PostgreSQL without
 * database-specific SQL. The unique constraint (actor_id, score_type, scope_key) with
 * NULLS NOT DISTINCT prevents duplicate GLOBAL rows at the database level.
 *
 * <p>
 * Upsert assumption: each {@code (actorId, scoreType, scopeKey)} triple is upserted at most
 * once per transaction. Calling {@code upsert()} twice for the same triple in a single
 * transaction may produce a duplicate row if Hibernate does not flush before the second
 * find. Under the default {@code FlushModeType.AUTO}, named queries trigger a flush, so
 * this is safe in practice. Do not disable auto-flush in a context that calls upsert.
 */
@ApplicationScoped
public class JpaActorTrustScoreRepository implements ActorTrustScoreRepository {

    @Inject
    @LedgerPersistenceUnit
    EntityManager em;

    @Override
    public Optional<ActorTrustScore> findByActorId(final String actorId) {
        return em.createNamedQuery("ActorTrustScore.findGlobalByActorId", ActorTrustScore.class)
                .setParameter("actorId", actorId)
                .setParameter("scoreType", ScoreType.GLOBAL)
                .getResultStream()
                .findFirst();
    }

    @Override
    public Optional<ActorTrustScore> findByActorIdAndTypeAndKey(
            final String actorId, final ScoreType scoreType, final String scopeKey) {
        if (scopeKey == null) {
            if (scoreType != ScoreType.GLOBAL) {
                throw new IllegalArgumentException(
                        "scopeKey must not be null for score type " + scoreType
                                + " — null scopeKey is only valid for GLOBAL scores");
            }
            return em.createNamedQuery("ActorTrustScore.findGlobalByActorId", ActorTrustScore.class)
                    .setParameter("actorId", actorId)
                    .setParameter("scoreType", scoreType)
                    .getResultStream()
                    .findFirst();
        }
        return em.createNamedQuery("ActorTrustScore.findByActorIdAndTypeAndKey", ActorTrustScore.class)
                .setParameter("actorId", actorId)
                .setParameter("scoreType", scoreType)
                .setParameter("scopeKey", scopeKey)
                .getResultStream()
                .findFirst();
    }

    @Override
    public List<ActorTrustScore> findByActorIdAndScoreType(
            final String actorId, final ScoreType scoreType) {
        return em.createNamedQuery("ActorTrustScore.findByActorIdAndScoreType", ActorTrustScore.class)
                .setParameter("actorId", actorId)
                .setParameter("scoreType", scoreType)
                .getResultList();
    }

    @Override
    @Transactional
    public void upsert(final String actorId, final ScoreType scoreType, final String scopeKey,
            final ActorType actorType, final double trustScore,
            final int decisionCount, final int overturnedCount,
            final double alpha, final double beta,
            final int attestationPositive, final int attestationNegative,
            final Instant lastComputedAt) {

        ActorTrustScore score = findByActorIdAndTypeAndKey(actorId, scoreType, scopeKey).orElse(null);
        if (score == null) {
            score = new ActorTrustScore();
            score.id = UUID.randomUUID();
            score.actorId = actorId;
            score.scoreType = scoreType;
            score.scopeKey = scopeKey;
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

    @Override
    @Transactional
    public void updateGlobalTrustScore(final String actorId, final double globalTrustScore) {
        findByActorId(actorId).ifPresent(score -> {
            score.globalTrustScore = globalTrustScore;
            em.merge(score);
        });
    }

    @Override
    public List<ActorTrustScore> findAll() {
        return em.createNamedQuery("ActorTrustScore.findAll", ActorTrustScore.class)
                .getResultList();
    }
}
