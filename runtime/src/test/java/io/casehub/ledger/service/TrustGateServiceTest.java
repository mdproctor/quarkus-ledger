package io.casehub.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.ActorTrustScore.ScoreType;
import io.casehub.ledger.api.model.ActorType;
import io.casehub.ledger.runtime.model.ActorTrustScore;
import io.casehub.ledger.runtime.repository.ActorTrustScoreRepository;
import io.casehub.ledger.runtime.service.TrustGateService;

/**
 * Pure unit tests for {@link TrustGateService} — no Quarkus runtime, no CDI.
 */
class TrustGateServiceTest {

    private static ActorTrustScoreRepository repoWith(final String actorId, final double trustScore) {
        final ActorTrustScore score = new ActorTrustScore();
        score.id = UUID.randomUUID();
        score.actorId = actorId;
        score.scoreType = ScoreType.GLOBAL;
        score.actorType = ActorType.AGENT;
        score.trustScore = trustScore;
        score.lastComputedAt = Instant.now();
        return new StubRepository(score);
    }

    private static ActorTrustScoreRepository emptyRepo() {
        return new StubRepository(null);
    }

    private static ActorTrustScoreRepository repoWith(
            final String actorId, final double globalScore,
            final String capabilityTag, final double capabilityScore) {
        final ActorTrustScore global = new ActorTrustScore();
        global.id = UUID.randomUUID();
        global.actorId = actorId;
        global.scoreType = ScoreType.GLOBAL;
        global.actorType = ActorType.AGENT;
        global.trustScore = globalScore;
        global.lastComputedAt = Instant.now();

        final ActorTrustScore capability = new ActorTrustScore();
        capability.id = UUID.randomUUID();
        capability.actorId = actorId;
        capability.scoreType = ScoreType.CAPABILITY;
        capability.scopeKey = capabilityTag;
        capability.actorType = ActorType.AGENT;
        capability.trustScore = capabilityScore;
        capability.lastComputedAt = Instant.now();

        return new StubRepository(global) {
            @Override
            public Optional<ActorTrustScore> findByActorIdAndTypeAndKey(
                    final String id, final ScoreType type, final String scopeKey) {
                if (actorId.equals(id) && ScoreType.CAPABILITY.equals(type)
                        && capabilityTag.equals(scopeKey)) {
                    return Optional.of(capability);
                }
                return Optional.empty();
            }
        };
    }

    private static class StubRepository implements ActorTrustScoreRepository {
        private final ActorTrustScore score;

        StubRepository(final ActorTrustScore score) {
            this.score = score;
        }

        @Override
        public Optional<ActorTrustScore> findByActorId(final String actorId) {
            return score != null && score.actorId.equals(actorId)
                    ? Optional.of(score)
                    : Optional.empty();
        }

        @Override
        public Optional<ActorTrustScore> findByActorIdAndTypeAndKey(
                final String actorId, final ScoreType scoreType, final String scopeKey) {
            return Optional.empty();
        }

        @Override
        public List<ActorTrustScore> findByActorIdAndScoreType(
                final String actorId, final ScoreType scoreType) {
            return List.of();
        }

        @Override
        public void upsert(final String actorId, final ScoreType scoreType, final String scopeKey,
                final ActorType actorType, final double trustScore,
                final int decisionCount, final int overturnedCount,
                final double alpha, final double beta,
                final int attestationPositive, final int attestationNegative,
                final Instant lastComputedAt) {
        }

        @Override
        public void updateGlobalTrustScore(final String actorId, final double globalTrustScore) {
        }

        @Override
        public List<ActorTrustScore> findAll() {
            return score != null ? List.of(score) : List.of();
        }
    }

    // ── meetsThreshold (global) ───────────────────────────────────────────────

    @Test
    void meetsThreshold_true_whenScoreAboveMin() {
        final TrustGateService gate = new TrustGateService(repoWith("actor-a", 0.8));

        assertThat(gate.meetsThreshold("actor-a", 0.7)).isTrue();
    }

    @Test
    void meetsThreshold_true_whenScoreEqualsMin() {
        final TrustGateService gate = new TrustGateService(repoWith("actor-a", 0.7));

        assertThat(gate.meetsThreshold("actor-a", 0.7)).isTrue();
    }

    @Test
    void meetsThreshold_false_whenScoreBelowMin() {
        final TrustGateService gate = new TrustGateService(repoWith("actor-a", 0.6));

        assertThat(gate.meetsThreshold("actor-a", 0.7)).isFalse();
    }

    @Test
    void meetsThreshold_false_whenNoScoreExists() {
        final TrustGateService gate = new TrustGateService(emptyRepo());

        assertThat(gate.meetsThreshold("unknown-actor", 0.0)).isFalse();
    }

    // ── meetsThreshold (capability overload — Phase 2) ────────────────────────

    @Test
    void meetsThreshold_withCapability_usesCapabilityScore_whenAvailable() {
        // global = 0.4 (would fail), capability "security-review" = 0.9 (should pass)
        final TrustGateService gate = new TrustGateService(
                repoWith("actor-x", 0.4, "security-review", 0.9));

        assertThat(gate.meetsThreshold("actor-x", "security-review", 0.8)).isTrue();
    }

    @Test
    void meetsThreshold_withCapability_capabilityScoreBelowThreshold() {
        // global = 0.9 (would pass), capability "style-review" = 0.3 (should fail)
        final TrustGateService gate = new TrustGateService(
                repoWith("actor-y", 0.9, "style-review", 0.3));

        assertThat(gate.meetsThreshold("actor-y", "style-review", 0.8)).isFalse();
    }

    @Test
    void meetsThreshold_withCapability_fallsBackToGlobal_whenNoCapabilityScore() {
        // No capability row for "unknown-tag" → falls back to global = 0.85
        final TrustGateService gate = new TrustGateService(repoWith("actor-z", 0.85));

        assertThat(gate.meetsThreshold("actor-z", "unknown-tag", 0.8)).isTrue();
        assertThat(gate.meetsThreshold("actor-z", "unknown-tag", 0.9)).isFalse();
    }

    @Test
    void meetsThreshold_withCapability_falseWhenNoScoreAtAll() {
        final TrustGateService gate = new TrustGateService(emptyRepo());

        assertThat(gate.meetsThreshold("ghost", "security-review", 0.0)).isFalse();
    }

    // ── currentScore (capability overload) ───────────────────────────────────

    @Test
    void currentScore_withCapability_returnsCapabilityScore() {
        final TrustGateService gate = new TrustGateService(
                repoWith("actor-q", 0.5, "security-review", 0.9));

        assertThat(gate.currentScore("actor-q", "security-review")).isPresent();
        assertThat(gate.currentScore("actor-q", "security-review").get()).isEqualTo(0.9);
    }

    @Test
    void currentScore_withCapability_emptyWhenNoCapabilityScore() {
        final TrustGateService gate = new TrustGateService(repoWith("actor-r", 0.7));

        assertThat(gate.currentScore("actor-r", "unknown-tag")).isEmpty();
    }

    // ── currentScore ─────────────────────────────────────────────────────────

    @Test
    void currentScore_returnsScore_whenActorKnown() {
        final TrustGateService gate = new TrustGateService(repoWith("actor-c", 0.75));

        assertThat(gate.currentScore("actor-c")).isPresent();
        assertThat(gate.currentScore("actor-c").get()).isEqualTo(0.75);
    }

    @Test
    void currentScore_returnsEmpty_whenActorUnknown() {
        final TrustGateService gate = new TrustGateService(emptyRepo());

        assertThat(gate.currentScore("ghost")).isEmpty();
    }
}
