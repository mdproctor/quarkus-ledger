package io.quarkiverse.ledger.runtime.privacy;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import io.quarkiverse.ledger.runtime.model.ActorIdentity;

/**
 * Built-in token-based actor identity provider backed by the {@code actor_identity} table.
 *
 * <p>
 * Not a CDI bean — constructed by {@link LedgerPrivacyProducer} when
 * {@code quarkus.ledger.identity.tokenisation.enabled=true}. The EntityManager
 * it receives is a CDI proxy that resolves to the current transaction's session
 * when its methods are called — all callers of this class operate within
 * an existing {@code @Transactional} boundary.
 */
public class InternalActorIdentityProvider implements ActorIdentityProvider {

    private final EntityManager em;

    public InternalActorIdentityProvider(final EntityManager em) {
        this.em = em;
    }

    /**
     * Returns the existing token for {@code rawActorId}, creating one if absent.
     * {@code null} input returns {@code null}.
     */
    @Override
    public String tokenise(final String rawActorId) {
        if (rawActorId == null) {
            return null;
        }
        return em.createNamedQuery("ActorIdentity.findByActorId", ActorIdentity.class)
                .setParameter("actorId", rawActorId)
                .getResultStream()
                .map(a -> a.token)
                .findFirst()
                .orElseGet(() -> {
                    final ActorIdentity identity = new ActorIdentity();
                    identity.token = UUID.randomUUID().toString();
                    identity.actorId = rawActorId;
                    em.persist(identity);
                    return identity.token;
                });
    }

    /**
     * Returns the existing token without creating one.
     * Returns {@code rawActorId} unchanged if no mapping exists.
     * {@code null} input returns {@code null}.
     */
    @Override
    public String tokeniseForQuery(final String rawActorId) {
        if (rawActorId == null) {
            return null;
        }
        return em.createNamedQuery("ActorIdentity.findByActorId", ActorIdentity.class)
                .setParameter("actorId", rawActorId)
                .getResultStream()
                .map(a -> a.token)
                .findFirst()
                .orElse(rawActorId);
    }

    /** Returns the real identity for a token, or empty if the mapping was erased. */
    @Override
    public Optional<String> resolve(final String token) {
        if (token == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(em.find(ActorIdentity.class, token))
                .map(a -> a.actorId);
    }

    /** Deletes the token→identity mapping. The token in existing entries becomes unresolvable. */
    @Override
    public void erase(final String rawActorId) {
        if (rawActorId == null) {
            return;
        }
        em.createNamedQuery("ActorIdentity.deleteByActorId")
                .setParameter("actorId", rawActorId)
                .executeUpdate();
    }
}
