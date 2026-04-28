package io.quarkiverse.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkiverse.ledger.runtime.model.ActorTrustScore;
import io.quarkiverse.ledger.runtime.model.ActorTrustScore.ScoreType;
import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.repository.ActorTrustScoreRepository;
import io.quarkiverse.ledger.runtime.service.TrustGateService;

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

    // ── meetsThreshold (capability overload — Phase 1 falls back to global) ──

    @Test
    void meetsThreshold_withCapability_fallsBackToGlobalScore_phase1() {
        final TrustGateService gate = new TrustGateService(repoWith("actor-b", 0.85));

        assertThat(gate.meetsThreshold("actor-b", "security-review", 0.80)).isTrue();
        assertThat(gate.meetsThreshold("actor-b", "security-review", 0.90)).isFalse();
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
