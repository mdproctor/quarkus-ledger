package io.casehub.ledger.runtime.privacy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import io.casehub.ledger.runtime.config.LedgerConfig;
import io.casehub.ledger.runtime.persistence.LedgerPersistenceUnit;
import io.quarkus.arc.DefaultBean;

/**
 * CDI producer for {@link ActorIdentityProvider} and {@link DecisionContextSanitiser}.
 *
 * <p>
 * Both producers are annotated {@link DefaultBean} — a consumer-supplied CDI bean of the
 * same type silently replaces the default without any configuration change.
 *
 * <p>
 * {@link ActorIdentityProvider}: returns {@link InternalActorIdentityProvider} when
 * {@code casehub.ledger.identity.tokenisation.enabled=true}; otherwise pass-through.
 * {@link DecisionContextSanitiser}: always returns pass-through; replace with a
 * custom bean to scrub PII from decision context blobs.
 */
@ApplicationScoped
public class LedgerPrivacyProducer {

    @Inject
    LedgerConfig config;

    @Inject
    @LedgerPersistenceUnit
    EntityManager em;

    @Produces
    @DefaultBean
    @ApplicationScoped
    public ActorIdentityProvider actorIdentityProvider() {
        if (config.identity().tokenisation().enabled()) {
            return new InternalActorIdentityProvider(em);
        }
        return new PassThroughActorIdentityProvider();
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    public DecisionContextSanitiser decisionContextSanitiser() {
        return new PassThroughDecisionContextSanitiser();
    }
}
