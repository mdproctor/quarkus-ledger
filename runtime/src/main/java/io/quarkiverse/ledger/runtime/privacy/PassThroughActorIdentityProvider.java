package io.quarkiverse.ledger.runtime.privacy;

import java.util.Optional;

/** Pass-through implementation — stores raw actor identities unchanged. */
public class PassThroughActorIdentityProvider implements ActorIdentityProvider {

    @Override
    public String tokenise(final String rawActorId) {
        return rawActorId;
    }

    @Override
    public String tokeniseForQuery(final String rawActorId) {
        return rawActorId;
    }

    @Override
    public Optional<String> resolve(final String token) {
        return Optional.ofNullable(token);
    }

    @Override
    public void erase(final String rawActorId) {
        // pass-through: no mapping to sever
    }
}
