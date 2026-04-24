package io.quarkiverse.ledger.runtime.privacy;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import io.quarkiverse.ledger.runtime.model.ActorIdentity;
import io.quarkiverse.ledger.runtime.persistence.LedgerPersistenceUnit;

/**
 * CDI bean for processing GDPR Art.17 erasure requests.
 *
 * <p>
 * Severs the token→identity mapping for the given actor. Ledger entries retaining
 * the token become permanently anonymous — the hash chain is intact; the personal
 * data link is gone. Returns an {@link ErasureResult} with diagnostic information.
 */
@ApplicationScoped
public class LedgerErasureService {

    @Inject
    ActorIdentityProvider actorIdentityProvider;

    @Inject
    @LedgerPersistenceUnit
    EntityManager em;

    /**
     * The outcome of an erasure request.
     *
     * @param rawActorId the identity that was requested for erasure
     * @param mappingFound {@code true} if a token→identity mapping existed and was severed
     * @param affectedEntryCount number of ledger entries whose {@code actorId} was the severed token;
     *        informational only — entries are not deleted
     */
    public record ErasureResult(String rawActorId, boolean mappingFound, long affectedEntryCount) {
    }

    /**
     * Process an erasure request for the given actor identity.
     *
     * <p>
     * If no mapping exists (tokenisation was never enabled for this actor, or the identity
     * was already erased), returns {@code mappingFound=false} with count 0.
     *
     * @param rawActorId the real actor identity to erase
     * @return the erasure result
     */
    @Transactional
    public ErasureResult erase(final String rawActorId) {
        final List<ActorIdentity> existing = em
                .createNamedQuery("ActorIdentity.findByActorId", ActorIdentity.class)
                .setParameter("actorId", rawActorId)
                .getResultList();

        if (existing.isEmpty()) {
            return new ErasureResult(rawActorId, false, 0L);
        }

        final String token = existing.get(0).token;

        final long count = em
                .createQuery("SELECT COUNT(e) FROM LedgerEntry e WHERE e.actorId = :token", Long.class)
                .setParameter("token", token)
                .getSingleResult();

        actorIdentityProvider.erase(rawActorId);

        return new ErasureResult(rawActorId, true, count);
    }
}
