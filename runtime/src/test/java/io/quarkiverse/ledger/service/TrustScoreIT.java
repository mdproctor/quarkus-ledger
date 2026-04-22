package io.quarkiverse.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.Test;

import io.quarkiverse.ledger.runtime.model.ActorTrustScore;
import io.quarkiverse.ledger.runtime.model.AttestationVerdict;
import io.quarkiverse.ledger.runtime.repository.LedgerEntryRepository;
import io.quarkiverse.ledger.runtime.service.TrustScoreJob;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

/**
 * End-to-end integration tests for the Bayesian Beta trust scoring pipeline.
 *
 * <p>
 * Runs the full {@link TrustScoreJob} against an H2 database populated with ledger
 * entries and attestations. Verifies score values, alpha/beta parameters, and
 * recency-weighting behaviour.
 */
@QuarkusTest
@TestProfile(TrustScoreIT.TrustScoreTestProfile.class)
class TrustScoreIT {

    public static class TrustScoreTestProfile implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "trust-score-test";
        }
    }

    @Inject
    TrustScoreJob trustScoreJob;

    @Inject
    LedgerEntryRepository repo;

    @Inject
    EntityManager em;

    // ── Happy path: no history → neutral score ────────────────────────────────

    @Test
    @Transactional
    void noAttestations_neutralScore() {
        final String actorId = "agent-no-att-" + UUID.randomUUID();

        seedDecision(actorId, Instant.now().minus(1, ChronoUnit.DAYS), null);
        seedDecision(actorId, Instant.now().minus(2, ChronoUnit.DAYS), null);

        trustScoreJob.runComputation();

        final ActorTrustScore score = em.find(ActorTrustScore.class, actorId);
        assertThat(score).isNotNull();
        assertThat(score.trustScore).isCloseTo(0.5, within(0.01));
        assertThat(score.alpha).isCloseTo(1.0, within(0.01));
        assertThat(score.beta).isCloseTo(1.0, within(0.01));
        assertThat(score.decisionCount).isEqualTo(2);
    }

    // ── Happy path: positive attestations → high score ────────────────────────

    @Test
    @Transactional
    void allPositiveAttestations_highScore() {
        final String actorId = "agent-positive-" + UUID.randomUUID();
        final Instant recentTime = Instant.now().minus(1, ChronoUnit.DAYS);

        seedDecision(actorId, recentTime, AttestationVerdict.SOUND);
        seedDecision(actorId, recentTime.minus(1, ChronoUnit.DAYS), AttestationVerdict.ENDORSED);
        seedDecision(actorId, recentTime.minus(2, ChronoUnit.DAYS), AttestationVerdict.SOUND);

        trustScoreJob.runComputation();

        final ActorTrustScore score = em.find(ActorTrustScore.class, actorId);
        assertThat(score).isNotNull();
        assertThat(score.trustScore).isGreaterThan(0.75);
        assertThat(score.alpha).isGreaterThan(score.beta);
        assertThat(score.attestationPositive).isEqualTo(3);
        assertThat(score.attestationNegative).isEqualTo(0);
    }

    // ── Happy path: negative attestations → low score ─────────────────────────

    @Test
    @Transactional
    void allNegativeAttestations_lowScore() {
        final String actorId = "agent-negative-" + UUID.randomUUID();
        final Instant recentTime = Instant.now().minus(1, ChronoUnit.DAYS);

        seedDecision(actorId, recentTime, AttestationVerdict.FLAGGED);
        seedDecision(actorId, recentTime.minus(1, ChronoUnit.DAYS), AttestationVerdict.CHALLENGED);

        trustScoreJob.runComputation();

        final ActorTrustScore score = em.find(ActorTrustScore.class, actorId);
        assertThat(score).isNotNull();
        assertThat(score.trustScore).isLessThan(0.4);
        assertThat(score.beta).isGreaterThan(score.alpha);
        assertThat(score.overturnedCount).isEqualTo(2);
    }

    // ── Correctness: alpha/beta values match expected Beta posterior ──────────

    @Test
    @Transactional
    void alphaBeta_matchExpectedPosterior() {
        // 1 SOUND attestation, effectively age=0 → recencyWeight ≈ 1.0
        // α = 1.0 (prior) + 1.0 = 2.0, β = 1.0 (prior), score = 2/3 ≈ 0.667
        final String actorId = "agent-posterior-" + UUID.randomUUID();
        final Instant now = Instant.now();

        seedDecisionWithAttestationAt(actorId, now.minus(1, ChronoUnit.HOURS),
                AttestationVerdict.SOUND, now.minus(1, ChronoUnit.HOURS));

        trustScoreJob.runComputation();

        final ActorTrustScore score = em.find(ActorTrustScore.class, actorId);
        assertThat(score).isNotNull();
        assertThat(score.trustScore).isCloseTo(2.0 / 3.0, within(0.02));
        assertThat(score.alpha).isCloseTo(2.0, within(0.05));
        assertThat(score.beta).isCloseTo(1.0, within(0.05));
    }

    // ── End-to-end: recency — old negative, recent positive → score > 0.5 ────

    @Test
    @Transactional
    void oldNegative_recentPositive_scoreAboveNeutral() {
        final String actorId = "agent-recency-" + UUID.randomUUID();
        final Instant now = Instant.now();

        // Old failure 180 days ago: recencyWeight = 2^(-180/90) = 0.25 → β += 0.25
        seedDecisionWithAttestationAt(actorId, now.minus(180, ChronoUnit.DAYS),
                AttestationVerdict.FLAGGED, now.minus(180, ChronoUnit.DAYS));

        // Recent endorsement 1 day ago: recencyWeight ≈ 1.0 → α += ~1.0
        seedDecisionWithAttestationAt(actorId, now.minus(1, ChronoUnit.DAYS),
                AttestationVerdict.SOUND, now.minus(1, ChronoUnit.DAYS));

        trustScoreJob.runComputation();

        final ActorTrustScore score = em.find(ActorTrustScore.class, actorId);
        assertThat(score).isNotNull();
        // α ≈ 2.0, β ≈ 1.25 → score ≈ 2.0/3.25 ≈ 0.615
        assertThat(score.trustScore).isGreaterThan(0.5);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private void seedDecision(final String actorId, final Instant decisionTime,
            final AttestationVerdict verdictOrNull) {
        LedgerTestFixtures.seedDecision(actorId, decisionTime, verdictOrNull, repo, em);
    }

    private void seedDecisionWithAttestationAt(final String actorId, final Instant decisionTime,
            final AttestationVerdict verdictOrNull, final Instant attestationTime) {
        LedgerTestFixtures.seedDecision(actorId, decisionTime, verdictOrNull, attestationTime, repo, em);
    }
}
