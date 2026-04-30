package io.casehub.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.ActorTrustScore.ScoreType;
import io.casehub.ledger.api.model.ActorType;
import io.casehub.ledger.runtime.repository.ActorTrustScoreRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

/**
 * Integration tests for {@link ActorTrustScoreRepository} — covers all score types
 * and verifies backward compatibility of the GLOBAL score path.
 */
@QuarkusTest
@TestProfile(ActorTrustScoreRepositoryIT.RepoTestProfile.class)
class ActorTrustScoreRepositoryIT {

    public static class RepoTestProfile implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "trust-repo-test";
        }
    }

    @Inject
    ActorTrustScoreRepository repo;

    // ── Backward compat: findByActorId still returns the GLOBAL score ─────────

    @Test
    @Transactional
    void findByActorId_returnsGlobalScore() {
        final String actorId = "actor-global-" + System.nanoTime();
        repo.upsert(actorId, ScoreType.GLOBAL, null, ActorType.AGENT,
                0.75, 5, 1, 2.0, 1.0, 4, 1, Instant.now());

        final var result = repo.findByActorId(actorId);

        assertThat(result).isPresent();
        assertThat(result.get().trustScore).isEqualTo(0.75);
        assertThat(result.get().scoreType).isEqualTo(ScoreType.GLOBAL);
        assertThat(result.get().scopeKey).isNull();
    }

    @Test
    @Transactional
    void findByActorId_returnsEmpty_whenOnlyCapabilityRowExists() {
        final String actorId = "actor-cap-only-" + System.nanoTime();
        repo.upsert(actorId, ScoreType.CAPABILITY, "security-review", ActorType.AGENT,
                0.85, 3, 0, 2.5, 1.0, 3, 0, Instant.now());

        // findByActorId is scoped to GLOBAL — must not return the CAPABILITY row
        assertThat(repo.findByActorId(actorId)).isEmpty();
    }

    // ── New: upsert is idempotent — second upsert updates, not inserts ────────

    @Test
    @Transactional
    void upsert_global_isIdempotent() {
        final String actorId = "actor-idem-" + System.nanoTime();
        repo.upsert(actorId, ScoreType.GLOBAL, null, ActorType.AGENT,
                0.5, 1, 0, 1.5, 1.5, 1, 0, Instant.now());
        repo.upsert(actorId, ScoreType.GLOBAL, null, ActorType.AGENT,
                0.8, 10, 2, 3.0, 1.0, 9, 1, Instant.now());

        final var all = repo.findAll();
        final long count = all.stream()
                .filter(s -> s.actorId.equals(actorId) && s.scoreType == ScoreType.GLOBAL)
                .count();
        assertThat(count).isEqualTo(1);
        assertThat(repo.findByActorId(actorId).get().trustScore).isEqualTo(0.8);
    }

    // ── New: scoped score queries ─────────────────────────────────────────────

    @Test
    @Transactional
    void findByActorIdAndTypeAndKey_returnsCapabilityScore() {
        final String actorId = "actor-scoped-" + System.nanoTime();
        repo.upsert(actorId, ScoreType.CAPABILITY, "security-review", ActorType.AGENT,
                0.85, 5, 0, 3.0, 1.0, 5, 0, Instant.now());

        final var result = repo.findByActorIdAndTypeAndKey(actorId, ScoreType.CAPABILITY, "security-review");

        assertThat(result).isPresent();
        assertThat(result.get().trustScore).isEqualTo(0.85);
        assertThat(result.get().scopeKey).isEqualTo("security-review");
    }

    @Test
    @Transactional
    void findByActorIdAndTypeAndKey_returnsEmpty_whenKeyDiffers() {
        final String actorId = "actor-wrongkey-" + System.nanoTime();
        repo.upsert(actorId, ScoreType.CAPABILITY, "security-review", ActorType.AGENT,
                0.85, 5, 0, 3.0, 1.0, 5, 0, Instant.now());

        assertThat(repo.findByActorIdAndTypeAndKey(actorId, ScoreType.CAPABILITY, "architecture-review"))
                .isEmpty();
    }

    @Test
    @Transactional
    void findByActorIdAndScoreType_returnsAllCapabilityRows() {
        final String actorId = "actor-multi-" + System.nanoTime();
        repo.upsert(actorId, ScoreType.CAPABILITY, "security-review", ActorType.AGENT,
                0.85, 5, 0, 3.0, 1.0, 5, 0, Instant.now());
        repo.upsert(actorId, ScoreType.CAPABILITY, "architecture-review", ActorType.AGENT,
                0.60, 3, 1, 2.0, 1.5, 2, 1, Instant.now());

        final var results = repo.findByActorIdAndScoreType(actorId, ScoreType.CAPABILITY);

        assertThat(results).hasSize(2);
        assertThat(results).extracting(s -> s.scopeKey)
                .containsExactlyInAnyOrder("security-review", "architecture-review");
    }

    @Test
    @Transactional
    void upsert_capability_isIdempotent() {
        final String actorId = "actor-cap-idem-" + System.nanoTime();
        repo.upsert(actorId, ScoreType.CAPABILITY, "security-review", ActorType.AGENT,
                0.5, 1, 0, 1.5, 1.5, 1, 0, Instant.now());
        repo.upsert(actorId, ScoreType.CAPABILITY, "security-review", ActorType.AGENT,
                0.9, 10, 0, 5.0, 1.0, 10, 0, Instant.now());

        final var results = repo.findByActorIdAndScoreType(actorId, ScoreType.CAPABILITY);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).trustScore).isEqualTo(0.9);
    }

    // ── updateGlobalTrustScore still works ────────────────────────────────────

    @Test
    @Transactional
    void updateGlobalTrustScore_updatesExistingGlobalRow() {
        final String actorId = "actor-eigentrust-" + System.nanoTime();
        repo.upsert(actorId, ScoreType.GLOBAL, null, ActorType.AGENT,
                0.75, 5, 0, 2.0, 1.0, 5, 0, Instant.now());

        repo.updateGlobalTrustScore(actorId, 0.42);

        assertThat(repo.findByActorId(actorId).get().globalTrustScore).isEqualTo(0.42);
    }
}
