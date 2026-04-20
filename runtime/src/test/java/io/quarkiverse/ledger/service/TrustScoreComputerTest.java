package io.quarkiverse.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.model.AttestationVerdict;
import io.quarkiverse.ledger.runtime.model.LedgerAttestation;
import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.service.TrustScoreComputer;

/**
 * Pure JUnit 5 unit tests for {@link TrustScoreComputer} — no Quarkus runtime, no CDI.
 *
 * <p>
 * EigenTrust-inspired score algorithm: each decision's score is derived from its attestation
 * verdict majority; recent decisions are weighted more heavily via exponential decay.
 * The final trust score is the weighted average, clamped to [0.0, 1.0].
 *
 * <p>
 * Uses a package-private concrete subclass {@code TestLedgerEntry} since {@link LedgerEntry}
 * is abstract. Only base-class fields are exercised — {@link TrustScoreComputer} operates
 * entirely on {@code id}, {@code actorId}, {@code actorType}, and {@code occurredAt}.
 */
class TrustScoreComputerTest {

    /** Half-life of 90 days — matches the default in {@code LedgerConfig.TrustScoreConfig}. */
    private final TrustScoreComputer computer = new TrustScoreComputer(90);
    private final Instant now = Instant.now();

    // -------------------------------------------------------------------------
    // Concrete subclass for unit testing (LedgerEntry is abstract)
    // -------------------------------------------------------------------------

    private static class TestLedgerEntry extends LedgerEntry {
    }

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    private TestLedgerEntry decision(final String actorId, final Instant occurredAt) {
        final TestLedgerEntry e = new TestLedgerEntry();
        e.id = UUID.randomUUID();
        e.subjectId = UUID.randomUUID();
        e.sequenceNumber = 1;
        e.entryType = LedgerEntryType.EVENT;
        e.actorId = actorId;
        e.actorType = ActorType.HUMAN;
        e.occurredAt = occurredAt;
        return e;
    }

    private LedgerAttestation attestation(final UUID entryId, final AttestationVerdict verdict) {
        final LedgerAttestation a = new LedgerAttestation();
        a.id = UUID.randomUUID();
        a.ledgerEntryId = entryId;
        a.attestorId = "peer";
        a.attestorType = ActorType.HUMAN;
        a.verdict = verdict;
        a.confidence = 0.9;
        return a;
    }

    // -------------------------------------------------------------------------
    // Empty history
    // -------------------------------------------------------------------------

    @Test
    void emptyHistory_returnsNeutralScore() {
        final TrustScoreComputer.ActorScore score = computer.compute(List.of(), Map.of(), now);

        assertThat(score.trustScore()).isCloseTo(0.5, within(0.01));
        assertThat(score.decisionCount()).isEqualTo(0);
    }

    @Test
    void emptyHistory_allCountersAreZero() {
        final TrustScoreComputer.ActorScore score = computer.compute(List.of(), Map.of(), now);

        assertThat(score.overturnedCount()).isEqualTo(0);
        assertThat(score.attestationPositive()).isEqualTo(0);
        assertThat(score.attestationNegative()).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // Single decision — no attestations
    // -------------------------------------------------------------------------

    @Test
    void singleCleanDecision_noAttestations_returnsHighScore() {
        final TestLedgerEntry d = decision("alice", now.minus(1, ChronoUnit.HOURS));

        final TrustScoreComputer.ActorScore score = computer.compute(List.of(d), Map.of(), now);

        assertThat(score.trustScore()).isCloseTo(1.0, within(0.01));
        assertThat(score.decisionCount()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Single decision — positive attestation
    // -------------------------------------------------------------------------

    @Test
    void singleDecisionWithSoundAttestation_returnsHighScore() {
        final TestLedgerEntry d = decision("alice", now.minus(1, ChronoUnit.HOURS));
        final LedgerAttestation a = attestation(d.id, AttestationVerdict.SOUND);

        final TrustScoreComputer.ActorScore score = computer.compute(
                List.of(d), Map.of(d.id, List.of(a)), now);

        assertThat(score.trustScore()).isCloseTo(1.0, within(0.01));
        assertThat(score.attestationPositive()).isEqualTo(1);
        assertThat(score.attestationNegative()).isEqualTo(0);
    }

    @Test
    void singleDecisionWithEndorsedAttestation_countsAsPositive() {
        final TestLedgerEntry d = decision("alice", now.minus(1, ChronoUnit.HOURS));
        final LedgerAttestation endorsed = attestation(d.id, AttestationVerdict.ENDORSED);

        final TrustScoreComputer.ActorScore score = computer.compute(
                List.of(d), Map.of(d.id, List.of(endorsed)), now);

        assertThat(score.attestationPositive()).isEqualTo(1);
        assertThat(score.attestationNegative()).isEqualTo(0);
        assertThat(score.trustScore()).isGreaterThanOrEqualTo(0.5);
    }

    // -------------------------------------------------------------------------
    // Single decision — negative attestation
    // -------------------------------------------------------------------------

    @Test
    void singleDecisionWithFlaggedAttestation_returnsLowScore() {
        final TestLedgerEntry d = decision("alice", now.minus(1, ChronoUnit.HOURS));
        final LedgerAttestation a = attestation(d.id, AttestationVerdict.FLAGGED);

        final TrustScoreComputer.ActorScore score = computer.compute(
                List.of(d), Map.of(d.id, List.of(a)), now);

        assertThat(score.trustScore()).isCloseTo(0.0, within(0.01));
        assertThat(score.overturnedCount()).isEqualTo(1);
        assertThat(score.attestationNegative()).isEqualTo(1);
    }

    @Test
    void singleDecisionWithChallengedAttestation_countsAsNegative() {
        final TestLedgerEntry d = decision("alice", now.minus(1, ChronoUnit.HOURS));
        final LedgerAttestation challenged = attestation(d.id, AttestationVerdict.CHALLENGED);

        final TrustScoreComputer.ActorScore score = computer.compute(
                List.of(d), Map.of(d.id, List.of(challenged)), now);

        assertThat(score.attestationNegative()).isEqualTo(1);
        assertThat(score.overturnedCount()).isEqualTo(1);
        assertThat(score.trustScore()).isLessThanOrEqualTo(0.5);
    }

    // -------------------------------------------------------------------------
    // Mixed attestations
    // -------------------------------------------------------------------------

    @Test
    void mixedAttestations_majorityNegative_returnsLowScore() {
        final TestLedgerEntry d = decision("alice", now.minus(1, ChronoUnit.HOURS));
        final LedgerAttestation sound = attestation(d.id, AttestationVerdict.SOUND);
        final LedgerAttestation f1 = attestation(d.id, AttestationVerdict.FLAGGED);
        final LedgerAttestation f2 = attestation(d.id, AttestationVerdict.FLAGGED);

        final TrustScoreComputer.ActorScore score = computer.compute(
                List.of(d), Map.of(d.id, List.of(sound, f1, f2)), now);

        assertThat(score.trustScore()).isCloseTo(0.0, within(0.01));
        assertThat(score.attestationPositive()).isEqualTo(1);
        assertThat(score.attestationNegative()).isEqualTo(2);
    }

    @Test
    void mixedAttestations_majorityPositive_returnsMidScore() {
        final TestLedgerEntry d = decision("alice", now.minus(1, ChronoUnit.HOURS));
        final LedgerAttestation s1 = attestation(d.id, AttestationVerdict.SOUND);
        final LedgerAttestation s2 = attestation(d.id, AttestationVerdict.SOUND);
        final LedgerAttestation f1 = attestation(d.id, AttestationVerdict.FLAGGED);

        final TrustScoreComputer.ActorScore score = computer.compute(
                List.of(d), Map.of(d.id, List.of(s1, s2, f1)), now);

        assertThat(score.trustScore()).isGreaterThan(0.0);
        assertThat(score.trustScore()).isLessThanOrEqualTo(1.0);
        assertThat(score.attestationPositive()).isEqualTo(2);
        assertThat(score.attestationNegative()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Multiple decisions
    // -------------------------------------------------------------------------

    @Test
    void multipleDecisions_allClean_returnsHighScore() {
        final TestLedgerEntry d1 = decision("alice", now.minus(1, ChronoUnit.DAYS));
        final TestLedgerEntry d2 = decision("alice", now.minus(2, ChronoUnit.DAYS));
        final TestLedgerEntry d3 = decision("alice", now.minus(3, ChronoUnit.DAYS));

        final TrustScoreComputer.ActorScore score = computer.compute(
                List.of(d1, d2, d3), Map.of(), now);

        assertThat(score.trustScore()).isCloseTo(1.0, within(0.01));
        assertThat(score.decisionCount()).isEqualTo(3);
    }

    @Test
    void multipleDecisions_mixed_returnsProportionalScore() {
        final TestLedgerEntry clean1 = decision("alice", now.minus(1, ChronoUnit.DAYS));
        final TestLedgerEntry clean2 = decision("alice", now.minus(2, ChronoUnit.DAYS));
        final TestLedgerEntry bad = decision("alice", now.minus(3, ChronoUnit.DAYS));
        final LedgerAttestation flagged = attestation(bad.id, AttestationVerdict.FLAGGED);

        final TrustScoreComputer.ActorScore score = computer.compute(
                List.of(clean1, clean2, bad),
                Map.of(bad.id, List.of(flagged)),
                now);

        assertThat(score.trustScore()).isGreaterThan(0.0);
        assertThat(score.trustScore()).isLessThan(1.0);
        assertThat(score.decisionCount()).isEqualTo(3);
        assertThat(score.overturnedCount()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Recency weighting
    // -------------------------------------------------------------------------

    @Test
    void recencyWeighting_recentPositiveOutweighsOldNegative() {
        final TestLedgerEntry recent = decision("alice", now.minus(1, ChronoUnit.DAYS));
        final LedgerAttestation recentSound = attestation(recent.id, AttestationVerdict.SOUND);

        final TestLedgerEntry old = decision("alice", now.minus(180, ChronoUnit.DAYS));
        final LedgerAttestation oldFlagged = attestation(old.id, AttestationVerdict.FLAGGED);

        final TrustScoreComputer.ActorScore score = computer.compute(
                List.of(recent, old),
                Map.of(recent.id, List.of(recentSound), old.id, List.of(oldFlagged)),
                now);

        assertThat(score.trustScore()).isGreaterThan(0.5);
    }

    @Test
    void halfLifeRespected_veryOldDecisionHasMinimalWeight() {
        final TrustScoreComputer shortHalfLife = new TrustScoreComputer(30);

        final TestLedgerEntry recent = decision("alice", now.minus(1, ChronoUnit.DAYS));
        final LedgerAttestation recentSound = attestation(recent.id, AttestationVerdict.SOUND);

        final TestLedgerEntry veryOld = decision("alice", now.minus(365, ChronoUnit.DAYS));
        final LedgerAttestation oldFlagged = attestation(veryOld.id, AttestationVerdict.FLAGGED);

        final TrustScoreComputer.ActorScore score = shortHalfLife.compute(
                List.of(recent, veryOld),
                Map.of(recent.id, List.of(recentSound), veryOld.id, List.of(oldFlagged)),
                now);

        assertThat(score.trustScore()).isGreaterThan(0.5);
    }

    // -------------------------------------------------------------------------
    // Score clamping
    // -------------------------------------------------------------------------

    @Test
    void score_alwaysWithinBounds() {
        final TestLedgerEntry d1 = decision("alice", now.minus(1, ChronoUnit.DAYS));
        final TestLedgerEntry d2 = decision("alice", now.minus(2, ChronoUnit.DAYS));
        final TestLedgerEntry d3 = decision("alice", now.minus(3, ChronoUnit.DAYS));
        final TestLedgerEntry d4 = decision("alice", now.minus(4, ChronoUnit.DAYS));
        final TestLedgerEntry d5 = decision("alice", now.minus(5, ChronoUnit.DAYS));

        final TrustScoreComputer.ActorScore score = computer.compute(
                List.of(d1, d2, d3, d4, d5), Map.of(), now);

        assertThat(score.trustScore()).isGreaterThanOrEqualTo(0.0);
        assertThat(score.trustScore()).isLessThanOrEqualTo(1.0);
    }

    // -------------------------------------------------------------------------
    // appealCount placeholder
    // -------------------------------------------------------------------------

    @Test
    void appealCount_alwaysZero() {
        final TestLedgerEntry d = decision("alice", now.minus(1, ChronoUnit.HOURS));

        final TrustScoreComputer.ActorScore score = computer.compute(List.of(d), Map.of(), now);

        assertThat(score.appealCount()).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // Zero halfLifeDays defaults to 90
    // -------------------------------------------------------------------------

    @Test
    void zeroHalfLifeDays_defaultsTo90() {
        final TrustScoreComputer computer0 = new TrustScoreComputer(0);
        final TrustScoreComputer computer90 = new TrustScoreComputer(90);

        final TestLedgerEntry d = decision("alice", now.minus(30, ChronoUnit.DAYS));
        final TrustScoreComputer.ActorScore s0 = computer0.compute(List.of(d), Map.of(), now);
        final TrustScoreComputer.ActorScore s90 = computer90.compute(List.of(d), Map.of(), now);

        assertThat(s0.trustScore()).isCloseTo(s90.trustScore(), within(0.01));
    }

    // ── forgiveness — disabled mode is identical to baseline ─────────────────

    @Test
    void forgiveness_disabled_identicalToBaseline() {
        // ForgivenessParams.disabled() must produce byte-for-byte identical results
        // to the original single-param constructor on the same input.
        final TrustScoreComputer withForgiveness = new TrustScoreComputer(90,
                TrustScoreComputer.ForgivenessParams.disabled());

        final TestLedgerEntry d1 = decision("alice", now.minus(10, ChronoUnit.DAYS));
        final TestLedgerEntry d2 = decision("alice", now.minus(20, ChronoUnit.DAYS));
        final LedgerAttestation flagged = attestation(d2.id, AttestationVerdict.FLAGGED);

        final TrustScoreComputer.ActorScore baseline = computer.compute(
                List.of(d1, d2), Map.of(d2.id, List.of(flagged)), now);
        final TrustScoreComputer.ActorScore forgivenessDisabled = withForgiveness.compute(
                List.of(d1, d2), Map.of(d2.id, List.of(flagged)), now);

        assertThat(forgivenessDisabled.trustScore())
                .isCloseTo(baseline.trustScore(), within(0.0001));
        assertThat(forgivenessDisabled.decisionCount()).isEqualTo(baseline.decisionCount());
        assertThat(forgivenessDisabled.overturnedCount()).isEqualTo(baseline.overturnedCount());
    }

    @Test
    void forgiveness_cleanDecisions_unaffected() {
        // Clean decisions (score = 1.0) must not be changed — forgiveness branch not entered
        final TrustScoreComputer withForgiveness = new TrustScoreComputer(90,
                new TrustScoreComputer.ForgivenessParams(true, 3, 30));

        final TestLedgerEntry d1 = decision("alice", now.minus(1, ChronoUnit.DAYS));
        final TestLedgerEntry d2 = decision("alice", now.minus(2, ChronoUnit.DAYS));

        final TrustScoreComputer.ActorScore baseline = computer.compute(List.of(d1, d2), Map.of(), now);
        final TrustScoreComputer.ActorScore forgiven = withForgiveness.compute(List.of(d1, d2), Map.of(), now);

        assertThat(forgiven.trustScore()).isCloseTo(baseline.trustScore(), within(0.0001));
    }

    @Test
    void forgiveness_singleTransientFailure_recovers() {
        // One flagged decision + subsequent clean history → forgiveness raises score above baseline
        final TrustScoreComputer withForgiveness = new TrustScoreComputer(90,
                new TrustScoreComputer.ForgivenessParams(true, 3, 30));

        final TestLedgerEntry failure = decision("alice", now.minus(30, ChronoUnit.DAYS));
        final LedgerAttestation flagged = attestation(failure.id, AttestationVerdict.FLAGGED);
        final TestLedgerEntry clean1 = decision("alice", now.minus(5, ChronoUnit.DAYS));
        final TestLedgerEntry clean2 = decision("alice", now.minus(3, ChronoUnit.DAYS));
        final TestLedgerEntry clean3 = decision("alice", now.minus(1, ChronoUnit.DAYS));

        final Map<UUID, List<LedgerAttestation>> attestations = Map.of(failure.id, List.of(flagged));
        final List<LedgerEntry> decisions = List.of(failure, clean1, clean2, clean3);

        final TrustScoreComputer.ActorScore baseline = computer.compute(decisions, attestations, now);
        final TrustScoreComputer.ActorScore forgiven = withForgiveness.compute(decisions, attestations, now);

        assertThat(forgiven.trustScore()).isGreaterThan(baseline.trustScore());
        assertThat(forgiven.trustScore()).isLessThanOrEqualTo(1.0);
    }

    @Test
    void forgiveness_oldFailure_substantiallyForgiven() {
        // A failure 60 days ago with halfLife=30 → recencyForgiveness ≈ 0.25
        // Score should be visibly higher than without forgiveness
        final TrustScoreComputer withForgiveness = new TrustScoreComputer(90,
                new TrustScoreComputer.ForgivenessParams(true, 3, 30));

        final TestLedgerEntry oldFailure = decision("alice", now.minus(60, ChronoUnit.DAYS));
        final LedgerAttestation flagged = attestation(oldFailure.id, AttestationVerdict.FLAGGED);

        final Map<UUID, List<LedgerAttestation>> attestations = Map.of(oldFailure.id, List.of(flagged));

        final TrustScoreComputer.ActorScore baseline = computer.compute(List.of(oldFailure), attestations, now);
        final TrustScoreComputer.ActorScore forgiven = withForgiveness.compute(List.of(oldFailure), attestations, now);

        // recencyForgiveness = 2^(-60/30) = 0.25, frequencyLeniency = 1.0 (1 ≤ 3)
        // adjustedScore = 0.0 + 0.25 × 1.0 = 0.25 — substantially higher than baseline 0.0
        assertThat(forgiven.trustScore()).isGreaterThan(baseline.trustScore() + 0.15);
    }

    @Test
    void forgiveness_repeatOffender_lessForgiven() {
        // 5 negative decisions exceeds frequencyThreshold=3 → frequencyLeniency = 0.5
        // Agent with 2 negatives must be forgiven MORE than agent with 5 negatives (same age)
        final TrustScoreComputer withForgiveness = new TrustScoreComputer(90,
                new TrustScoreComputer.ForgivenessParams(true, 3, 30));
        final Instant failureTime = now.minus(20, ChronoUnit.DAYS);

        // Few negatives: 2 flagged decisions (below threshold)
        final TestLedgerEntry fail1 = decision("agent-a", failureTime);
        final TestLedgerEntry fail2 = decision("agent-a", failureTime.minusSeconds(60));
        final LedgerAttestation f1 = attestation(fail1.id, AttestationVerdict.FLAGGED);
        final LedgerAttestation f2 = attestation(fail2.id, AttestationVerdict.FLAGGED);
        final TrustScoreComputer.ActorScore fewNegatives = withForgiveness.compute(
                List.of(fail1, fail2),
                Map.of(fail1.id, List.of(f1), fail2.id, List.of(f2)),
                now);

        // Many negatives: 5 flagged decisions (above threshold)
        final TestLedgerEntry fail3 = decision("agent-b", failureTime);
        final TestLedgerEntry fail4 = decision("agent-b", failureTime.minusSeconds(60));
        final TestLedgerEntry fail5 = decision("agent-b", failureTime.minusSeconds(120));
        final TestLedgerEntry fail6 = decision("agent-b", failureTime.minusSeconds(180));
        final TestLedgerEntry fail7 = decision("agent-b", failureTime.minusSeconds(240));
        final LedgerAttestation f3 = attestation(fail3.id, AttestationVerdict.FLAGGED);
        final LedgerAttestation f4 = attestation(fail4.id, AttestationVerdict.FLAGGED);
        final LedgerAttestation f5 = attestation(fail5.id, AttestationVerdict.FLAGGED);
        final LedgerAttestation f6 = attestation(fail6.id, AttestationVerdict.FLAGGED);
        final LedgerAttestation f7 = attestation(fail7.id, AttestationVerdict.FLAGGED);
        final TrustScoreComputer.ActorScore manyNegatives = withForgiveness.compute(
                List.of(fail3, fail4, fail5, fail6, fail7),
                Map.of(fail3.id, List.of(f3), fail4.id, List.of(f4),
                        fail5.id, List.of(f5), fail6.id, List.of(f6), fail7.id, List.of(f7)),
                now);

        // Agent with few negatives must receive more benefit from forgiveness
        assertThat(fewNegatives.trustScore()).isGreaterThan(manyNegatives.trustScore());
    }

    @Test
    void forgiveness_recentFailure_partiallyForgiven() {
        // A failure that just happened → recencyForgiveness ≈ 1.0 → full F applied
        // Score is raised from 0.0 toward 1.0 (not left at 0.0)
        final TrustScoreComputer withForgiveness = new TrustScoreComputer(90,
                new TrustScoreComputer.ForgivenessParams(true, 3, 30));

        final TestLedgerEntry recentFailure = decision("alice", now.minus(1, ChronoUnit.HOURS));
        final LedgerAttestation flagged = attestation(recentFailure.id, AttestationVerdict.FLAGGED);

        final TrustScoreComputer.ActorScore baseline = computer.compute(
                List.of(recentFailure), Map.of(recentFailure.id, List.of(flagged)), now);
        final TrustScoreComputer.ActorScore forgiven = withForgiveness.compute(
                List.of(recentFailure), Map.of(recentFailure.id, List.of(flagged)), now);

        // baseline = 0.0; with forgiveness enabled and recency ≈ 1.0, score must be > 0.0
        assertThat(forgiven.trustScore()).isGreaterThan(baseline.trustScore());
        assertThat(forgiven.trustScore()).isGreaterThan(0.5); // recencyF ≈ 1.0, frequencyF = 1.0 → F ≈ 1.0
    }

    // ── Beta model: these tests FAIL with the current algorithm ──────────────

    @Test
    void beta_unattestedDecision_scoresNeutral() {
        // Current model: unattempted decisions score 1.0 (clean).
        // Beta model: unattested decisions contribute nothing — prior gives 0.5.
        final TestLedgerEntry d = decision("alice", now.minus(1, ChronoUnit.HOURS));

        final TrustScoreComputer.ActorScore score = computer.compute(List.of(d), Map.of(), now);

        assertThat(score.trustScore()).isCloseTo(0.5, within(0.01));
    }

    @Test
    void beta_onePositiveAttestation_scoreNotAtMax() {
        // Current model: 1 positive → score 1.0 (maximum confidence).
        // Beta model: 1 positive → α=2, β=1 → score ≈ 0.667 (uncertainty acknowledged).
        final TestLedgerEntry d = decision("alice", now.minus(1, ChronoUnit.HOURS));
        final LedgerAttestation a = attestation(d.id, AttestationVerdict.SOUND);
        a.occurredAt = now.minus(1, ChronoUnit.HOURS);

        final TrustScoreComputer.ActorScore score = computer.compute(
                List.of(d), Map.of(d.id, List.of(a)), now);

        assertThat(score.trustScore()).isLessThan(0.9); // Beta: ~0.667, not 1.0
    }

    @Test
    void beta_oneNegativeAttestation_scoreAboveZero() {
        // Current model: 1 negative → score 0.0 (harshest penalty).
        // Beta model: 1 negative → α=1, β=2 → score ≈ 0.333 (uncertainty acknowledged).
        final TestLedgerEntry d = decision("alice", now.minus(1, ChronoUnit.HOURS));
        final LedgerAttestation a = attestation(d.id, AttestationVerdict.FLAGGED);
        a.occurredAt = now.minus(1, ChronoUnit.HOURS);

        final TrustScoreComputer.ActorScore score = computer.compute(
                List.of(d), Map.of(d.id, List.of(a)), now);

        assertThat(score.trustScore()).isGreaterThan(0.1); // Beta: ~0.333, not 0.0
    }

    @Test
    void beta_moreEvidenceYieldsHigherConfidence() {
        // Current model: 1 positive and 100 positives both score 1.0.
        // Beta model: 1 positive → 0.667; 100 positives → 0.990. Evidence matters.
        final TestLedgerEntry d1 = decision("alice", now.minus(1, ChronoUnit.HOURS));
        final LedgerAttestation onePositive = attestation(d1.id, AttestationVerdict.SOUND);
        onePositive.occurredAt = now.minus(1, ChronoUnit.HOURS);

        final TestLedgerEntry d2 = decision("bob", now.minus(1, ChronoUnit.HOURS));
        final java.util.List<LedgerAttestation> manyPositive = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final LedgerAttestation a = attestation(d2.id, AttestationVerdict.SOUND);
            a.occurredAt = now.minus(1, ChronoUnit.HOURS);
            manyPositive.add(a);
        }

        final TrustScoreComputer.ActorScore scoreOne = computer.compute(
                List.of(d1), Map.of(d1.id, List.of(onePositive)), now);
        final TrustScoreComputer.ActorScore scoreMany = computer.compute(
                List.of(d2), Map.of(d2.id, manyPositive), now);

        assertThat(scoreMany.trustScore()).isGreaterThan(scoreOne.trustScore());
    }
}
