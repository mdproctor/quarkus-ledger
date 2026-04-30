package io.casehub.ledger.runtime.model;

import jakarta.persistence.Entity;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Bayesian Beta trust score for a decision-making actor, scoped by score type.
 *
 * <p>
 * One row per {@code (actor_id, score_type, scope_key)} triple:
 * <ul>
 * <li>{@code GLOBAL} — one row per actor; classic score across all decisions. {@code scope_key} is null.</li>
 * <li>{@code CAPABILITY} — one row per (actor, capability tag); requires #61.</li>
 * <li>{@code DIMENSION} — one row per (actor, trust dimension); requires #62.</li>
 * </ul>
 *
 * <p>
 * Plain {@code @Entity} — queries via {@code @NamedQuery} + EntityManager.
 */
@Entity
@Table(name = "actor_trust_score", uniqueConstraints = @UniqueConstraint(name = "uq_actor_trust_score_key", columnNames = {
        "actor_id", "score_type", "scope_key" }))
@NamedQuery(name = "ActorTrustScore.findAll", query = "SELECT s FROM ActorTrustScore s")
@NamedQuery(name = "ActorTrustScore.findGlobalByActorId", query = "SELECT s FROM ActorTrustScore s WHERE s.actorId = :actorId AND s.scoreType = :scoreType AND s.scopeKey IS NULL")
@NamedQuery(name = "ActorTrustScore.findByActorIdAndScoreType", query = "SELECT s FROM ActorTrustScore s WHERE s.actorId = :actorId AND s.scoreType = :scoreType")
@NamedQuery(name = "ActorTrustScore.findByActorIdAndTypeAndKey", query = "SELECT s FROM ActorTrustScore s WHERE s.actorId = :actorId AND s.scoreType = :scoreType AND s.scopeKey = :scopeKey")
public class ActorTrustScore extends io.casehub.ledger.api.model.ActorTrustScore {

}
