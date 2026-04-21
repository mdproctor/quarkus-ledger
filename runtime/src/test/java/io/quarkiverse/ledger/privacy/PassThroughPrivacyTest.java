package io.quarkiverse.ledger.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.quarkiverse.ledger.runtime.privacy.PassThroughActorIdentityProvider;
import io.quarkiverse.ledger.runtime.privacy.PassThroughDecisionContextSanitiser;

class PassThroughPrivacyTest {

    private final PassThroughActorIdentityProvider provider = new PassThroughActorIdentityProvider();
    private final PassThroughDecisionContextSanitiser sanitiser = new PassThroughDecisionContextSanitiser();

    @Test
    void tokenise_returnsRawActorId_unchanged() {
        assertThat(provider.tokenise("alice@example.com")).isEqualTo("alice@example.com");
    }

    @Test
    void tokenise_nullSafe_returnsNull() {
        assertThat(provider.tokenise(null)).isNull();
    }

    @Test
    void tokeniseForQuery_returnsRawActorId_unchanged() {
        assertThat(provider.tokeniseForQuery("alice@example.com")).isEqualTo("alice@example.com");
    }

    @Test
    void resolve_returnsTokenAsIdentity() {
        assertThat(provider.resolve("some-token")).isEqualTo(Optional.of("some-token"));
    }

    @Test
    void resolve_null_returnsEmpty() {
        assertThat(provider.resolve(null)).isEmpty();
    }

    @Test
    void erase_isNoOp_noException() {
        provider.erase("alice@example.com");
    }

    @Test
    void sanitise_returnsJson_unchanged() {
        final String json = "{\"name\":\"Alice\",\"riskScore\":42}";
        assertThat(sanitiser.sanitise(json)).isEqualTo(json);
    }

    @Test
    void sanitise_null_returnsNull() {
        assertThat(sanitiser.sanitise(null)).isNull();
    }
}
