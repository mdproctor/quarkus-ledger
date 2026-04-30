package io.casehub.ledger.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.runtime.privacy.ActorIdentityProvider;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

/**
 * Integration tests for {@link io.casehub.ledger.runtime.privacy.InternalActorIdentityProvider}.
 *
 * <p>
 * Runs with tokenisation enabled against an isolated H2 database.
 * Verifies token creation, idempotency, resolution, and erasure behaviour.
 */
@QuarkusTest
@TestProfile(InternalActorIdentityProviderIT.PseudonymisationProfile.class)
class InternalActorIdentityProviderIT {

    public static class PseudonymisationProfile implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "pseudonymisation-test";
        }
    }

    @Inject
    ActorIdentityProvider provider;

    // ── Happy path: tokenise creates a token ──────────────────────────────────

    @Test
    @Transactional
    void tokenise_createsToken_differentFromRawActorId() {
        final String token = provider.tokenise("alice@example.com");
        assertThat(token).isNotNull().isNotEqualTo("alice@example.com");
    }

    // ── Correctness: same actorId always maps to same token ───────────────────

    @Test
    @Transactional
    void tokenise_sameActorId_returnsSameToken() {
        final String token1 = provider.tokenise("bob@example.com");
        final String token2 = provider.tokenise("bob@example.com");
        assertThat(token1).isEqualTo(token2);
    }

    // ── Correctness: different actorIds get different tokens ──────────────────

    @Test
    @Transactional
    void tokenise_differentActorIds_returnsDifferentTokens() {
        final String tokenAlice = provider.tokenise("alice-" + java.util.UUID.randomUUID());
        final String tokenBob = provider.tokenise("bob-" + java.util.UUID.randomUUID());
        assertThat(tokenAlice).isNotEqualTo(tokenBob);
    }

    // ── Happy path: null input returns null ───────────────────────────────────

    @Test
    @Transactional
    void tokenise_null_returnsNull() {
        assertThat(provider.tokenise(null)).isNull();
    }

    // ── Happy path: tokeniseForQuery returns existing token ───────────────────

    @Test
    @Transactional
    void tokeniseForQuery_existingActor_returnsToken() {
        final String actorId = "carol-" + java.util.UUID.randomUUID();
        final String token = provider.tokenise(actorId);
        assertThat(provider.tokeniseForQuery(actorId)).isEqualTo(token);
    }

    // ── Correctness: tokeniseForQuery does not create for unknown actor ────────

    @Test
    @Transactional
    void tokeniseForQuery_unknownActor_returnsRawActorId() {
        final String unknown = "unknown-" + java.util.UUID.randomUUID();
        assertThat(provider.tokeniseForQuery(unknown)).isEqualTo(unknown);
    }

    // ── Happy path: resolve returns the real identity ─────────────────────────

    @Test
    @Transactional
    void resolve_existingToken_returnsRealIdentity() {
        final String actorId = "dave-" + java.util.UUID.randomUUID();
        final String token = provider.tokenise(actorId);
        assertThat(provider.resolve(token)).isEqualTo(Optional.of(actorId));
    }

    // ── Correctness: resolve returns empty for unknown token ──────────────────

    @Test
    @Transactional
    void resolve_unknownToken_returnsEmpty() {
        assertThat(provider.resolve("no-such-token")).isEmpty();
    }

    // ── Correctness: resolve returns empty for null ───────────────────────────

    @Test
    @Transactional
    void resolve_null_returnsEmpty() {
        assertThat(provider.resolve(null)).isEmpty();
    }

    // ── Happy path: erase severs the mapping ─────────────────────────────────

    @Test
    @Transactional
    void erase_severed_resolveReturnsEmpty() {
        final String actorId = "eve-" + java.util.UUID.randomUUID();
        final String token = provider.tokenise(actorId);

        provider.erase(actorId);

        assertThat(provider.resolve(token)).isEmpty();
    }

    // ── Correctness: erase of unknown actorId does not throw ─────────────────

    @Test
    @Transactional
    void erase_unknownActor_noException() {
        provider.erase("never-registered-" + java.util.UUID.randomUUID());
    }

    // ── Correctness: tokeniseForQuery after erase returns raw actorId ─────────

    @Test
    @Transactional
    void tokeniseForQuery_afterErase_returnsRawActorId() {
        final String actorId = "frank-" + java.util.UUID.randomUUID();
        provider.tokenise(actorId);
        provider.erase(actorId);

        assertThat(provider.tokeniseForQuery(actorId)).isEqualTo(actorId);
    }
}
