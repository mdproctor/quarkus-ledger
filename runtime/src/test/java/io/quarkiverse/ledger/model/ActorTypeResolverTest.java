package io.casehub.ledger.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.ledger.api.model.ActorTypeResolver;

class ActorTypeResolverTest {

    // ── AGENT ─────────────────────────────────────────────────────────────────

    @Test
    void versionedPersona_simpleVersion_isAgent() {
        assertThat(ActorTypeResolver.resolve("claude:analyst@v1")).isEqualTo(ActorType.AGENT);
    }

    @Test
    void versionedPersona_semver_isAgent() {
        assertThat(ActorTypeResolver.resolve("claude:analyst@v1.2.3")).isEqualTo(ActorType.AGENT);
    }

    @Test
    void agentPrefix_isAgent() {
        assertThat(ActorTypeResolver.resolve("agent:worker-1")).isEqualTo(ActorType.AGENT);
    }

    // ── SYSTEM ────────────────────────────────────────────────────────────────

    @Test
    void exactSystem_isSystem() {
        assertThat(ActorTypeResolver.resolve("system")).isEqualTo(ActorType.SYSTEM);
    }

    @Test
    void systemColon_isSystem() {
        assertThat(ActorTypeResolver.resolve("system:scheduler")).isEqualTo(ActorType.SYSTEM);
    }

    @Test
    void nullActorId_isSystem() {
        assertThat(ActorTypeResolver.resolve(null)).isEqualTo(ActorType.SYSTEM);
    }

    @Test
    void blankActorId_isSystem() {
        assertThat(ActorTypeResolver.resolve("")).isEqualTo(ActorType.SYSTEM);
    }

    // ── HUMAN ─────────────────────────────────────────────────────────────────

    @Test
    void plainUsername_isHuman() {
        assertThat(ActorTypeResolver.resolve("alice")).isEqualTo(ActorType.HUMAN);
    }

    @Test
    void emailAddress_isHuman() {
        assertThat(ActorTypeResolver.resolve("alice@example.com")).isEqualTo(ActorType.HUMAN);
    }
}
