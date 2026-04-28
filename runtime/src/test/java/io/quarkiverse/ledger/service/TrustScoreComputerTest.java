package io.quarkiverse.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
 * Bayesian Beta model: prior Beta(1,1). Each attestation contributes a recency-weighted
 * increment to α (positive) or β (negative). Score = α/(α+β). Unattested decisions
 * contribute nothing — only the prior applies.
 */
class TrustScoreComputerTest {

    private final TrustScoreComputer computer = new TrustScoreComputer(90);
    private final Instant now = Instant.now();

    // ── Concrete subclass (LedgerEntry is abstract) ───────────────────────────

    private static class TestLedgerEntry extends LedgerEntry {
    }

    // ── Fixture helpers ───────────────────────────────────────────────────────

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

    /** Attestation with occurredAt = now (zero age, full recency weight = 1.0). */
    private LedgerAttestation attestation(final UUID entryId, final AttestationVerdict verdict) {
        return attestation(entryId, verdict, now);
    }

    /** Attestation with explicit occurredAt for recency-sensitive tests. */
    private LedgerAttestation attestation(final UUID entryId, final AttestationVerdict verdict,
            final Instant occurredAt) {
        final LedgerAttestation a = new LedgerAttestation();
        a.id = UUID.randomUUID();
        a.ledgerEntryId = entryId;
        a.attestorId = "peer";
        a.attestorType = ActorType.HUMAN;
        a.verdict = verdict;
        a.confidence = 1.0;
        a.occurredAt = occurredAt;
        return a;
    }

    /** Attestation at occurredAt=now with explicit confidence for confidence-weighting tests. */
    private LedgerAttestation attestation(final UUID entryId, final AttestationVerdict verdict,
            final double confidence) {
        final LedgerAttestation a = new LedgerAttestation();
        a.id = UUID.randomUUID();
        a.ledgerEntryId = entryId;
        a.attestorId = "peer";
        a.attestorType = ActorType.HUMAN;
        a.verdict = verdict;
        a.confidence = confidence;
        a.occurredAt = now;
        return a;
    }

    // ── Empty history ─────────────────────────────────────────────────────────

    @Test
    void emptyHistory_returnsNeutralScore() {
        final TrustScoreComputer.ActorScore score = computer.compute(List.of(), Map.of(), now);

        assertThat(score.trustScore()).isCloseTo(0.5, within(0.01));
        assertThat(score.decisionCount()).isEqualTo(0);
    }

    @Test
    void emptyHistory_priorAlphaBeta_one() {
        final TrustScoreComputer.ActorScore score = computer.compute(List.of(), Map.of(), now);

        assertThat(score.alpha()).isCloseTo(1.0, within(0.001));
        assertThat(score.beta()).isCloseTo(1.0, within(0.001));
    }

    @Test
    void emptyHistory_countersAreZero() {
        final TrustScoreComputer.ActorScore score = computer.compute(List.of(), Map.of(), now);

        assertThat(score.overturnedCount()).isEqualTo(0);
        assertThat(score.attestationPositive()).isEqualTo(0);
        assertThat(score.attestationNegative()).isEqualTo(0);
    }

    // ── Unattested decisions ──────────────────────────────────────────────────

    @Test
    void unattestedDecision_scoresNeutral() {
        // Unattested decisions contribute nothing. Prior Beta(1,1) → 0.5.
        final TestLedgerEntry d = decision("alice", now.minus(1, ChronoUnit.HOURS));

        final TrustScoreComputer.ActorScore score = computer.compute(List.of(d), Map.of(), now);

        assertThat(score.trustScore()).isCloseTo(0.5, within(0.01));
        assertThat(score.decisionCount()).isEqualTo(1);
    }

    @Test
    void multipleUnattestedDecisions_stillNeutral() {
        final TestLedgerEntry d1 = decision("alice", now.minus(1, ChronoUnit.DAYS));
        final TestLedgerEntry d2 = decision("alice", now.minus(2, ChronoUnit.DAYS));
        final TestLedgerEntry d3 = decision("alice", now.minus(3, ChronoUnit.DAYS));

        final TrustScoreComputer.ActorScore score = computer.compute(
                List.of(d1, d2, d3), Map.of(), now);

        assertThat(score.trustScore()).isCloseTo(0.5, within(0.01));
        assertThat(score.decisionCount()).isEqualTo(3);
    }

    // ── Single positive attestation ───────────────────────────────────────────

    @Test
    void onePositiveAttestation_scoreAboveNeutralNotAtMax() {
        // α=1+1=2, β=1 → score = 2/3 ≈ 0.667. Not 1.0 — uncertainty acknowledged.
        final TestLedgerEntry d = decision("alice", now.minus(1, ChronoUnit.HOURS));
        final LedgerAttestation a = attestation(d.id, AttestationVerdict.SOUND);

        final TrustScoreComputer.ActorScore score = computer.compute(
                List.of(d), Map.of(d.id, List.of(a)), now);

        assertThat(score.trustScore()).isCloseTo(2.0 / 3.0, within(0.01));
        assertThat(score.alpha()).isCloseTo(2.0, within(0.01));
        assertThat(score.beta()).isCloseTo(1.0, within(0.01));
        assertThat(score.attestationPositive()).isEqualTo(1);
        assertThat(score.attestationNegative()).isEqualTo(0);
    }

    @Test
    void endorsedAttestation_countsAsPositive() {
        final TestLedgerEntry d = decision("alice", now.minus(1, ChronoUnit.HOURS));
        final LedgerAttestation a = attestation(d.id, AttestationVerdict.ENDORSED);

        final TrustScoreComputer.ActorScore score = computer.compute(
                List.of(d), Map.of(d.id, List.of(a)), now);

        assertThat(score.attestationPositive()).isEqualTo(1);
        assertThat(score.trustScore()).isGreaterThan(0.5);
    }

    // ── Single negative attestation ───────────────────────────────────────────

    @Test
    void oneNegativeAttestation_scoreBelowNeutralNotAtMin() {
        // α=1, β=1+1=2 → score = 1/3 ≈ 0.333. Not 0.0 — uncertainty acknowledged.
        final TestLedgerEntry d = decision("alice", now.minus(1, ChronoUnit.HOURS));
        final LedgerAttestation a = attestation(d.id, AttestationVerdict.FLAGGED);

        final TrustScoreComputer.ActorScore score = computer.compute(
                List.of(d), Map.of(d.id, List.of(a)), now);

        assertThat(score.trustScore()).isCloseTo(1.0 / 3.0, within(0.01));
        assertThat(score.alpha()).isCloseTo(1.0, within(0.01));
        assertThat(score.beta()).isCloseTo(2.0, within(0.01));
        assertThat(score.overturnedCount()).isEqualTo(1);
        assertThat(score.attestationNegative()).isEqualTo(1);
    }

    @Test
    void challengedAttestation_countsAsNegative() {
        final TestLedgerEntry d = decision("alice", now.minus(1, ChronoUnit.HOURS));
        final LedgerAttestation a = attestation(d.id, AttestationVerdict.CHALLENGED);

        final TrustScoreComputer.ActorScore score = computer.compute(
                List.of(d), Map.of(d.id, List.of(a)), now);

        assertThat(score.attestationNegative()).isEqualTo(1);
        assertThat(score.trustScore()).isLessThan(0.5);
    }

    // ── Uncertainty capture ───────────────────────────────────────────────────

    @Test
    void moreEvidenceYieldsHigherConfidence() {
        // Key Beta property: 1 positive → 0.667; 100 positives → 0.990.
        final TestLedgerEntry d1 = decision("alice", now.minus(1, ChronoUnit.HOURS));
        final LedgerAttestation onePositive = attestation(d1.id, AttestationVerdict.SOUND);

        final TestLedgerEntry d2 = decision("bob", now.minus(1, ChronoUnit.HOURS));
        final List<LedgerAttestation> manyPositive = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            manyPositive.add(attestation(d2.id, AttestationVerdict.SOUND));
        }

        final TrustScoreComputer.ActorScore scoreOne = computer.compute(
                List.of(d1), Map.of(d1.id, List.of(onePositive)), now);
        final TrustScoreComputer.ActorScore scoreMany = computer.compute(
                List.of(d2), Map.of(d2.id, manyPositive), now);

        assertThat(scoreMany.trustScore()).isGreaterThan(scoreOne.trustScore());
        assertThat(scoreMany.trustScore()).isCloseTo(101.0 / 102.0, within(0.01));
    }

    // ── Mixed attestations ────────────────────────────────────────────────────

    @Test
    void mixedAttestations_twoPositiveOneNegative_scoreAboveNeutral() {
        // α=1+2=3, β=1+1=2 → score = 3/5 = 0.6
        final TestLedgerEntry d = decision("alice", now.minus(1, ChronoUnit.HOURS));
        final LedgerAttestation s1 = attestation(d.id, AttestationVerdict.SOUND);
        final LedgerAttestation s2 = attestation(d.id, AttestationVerdict.SOUND);
        final LedgerAttestation f1 = attestation(d.id, AttestationVerdict.FLAGGED);

        final TrustScoreComputer.ActorScore score = computer.compute(
                List.of(d), Map.of(d.id, List.of(s1, s2, f1)), now);

        assertThat(score.trustScore()).isCloseTo(0.6, within(0.01));
        assertThat(score.attestationPositive()).isEqualTo(2);
        assertThat(score.attestationNegative()).isEqualTo(1);
    }

    @Test
    void mixedAttestations_onePositiveTwoNegative_scoreBelowNeutral() {
        // α=1+1=2, β=1+2=3 → score = 2/5 = 0.4
        final TestLedgerEntry d = decision("alice", now.minus(1, ChronoUnit.HOURS));
        final LedgerAttestation sound = attestation(d.id, AttestationVerdict.SOUND);
        final LedgerAttestation f1 = attestation(d.id, AttestationVerdict.FLAGGED);
        final LedgerAttestation f2 = attestation(d.id, AttestationVerdict.FLAGGED);

        final TrustScoreComputer.ActorScore score = computer.compute(
                List.of(d), Map.of(d.id, List.of(sound, f1, f2)), now);

        assertThat(score.trustScore()).isCloseTo(0.4, within(0.01));
        assertThat(score.attestationPositive()).isEqualTo(1);
        assertThat(score.attestationNegative()).isEqualTo(2);
    }

    // ── Multiple decisions ────────────────────────────────────────────────────

    @Test
    void multipleDecisions_mixedHistory_correctCounters() {
        final TestLedgerEntry clean1 = decision("alice", now.minus(1, ChronoUnit.DAYS));
        final TestLedgerEntry clean2 = decision("alice", now.minus(2, ChronoUnit.DAYS));
        final TestLedgerEntry bad = decision("alice", now.minus(3, ChronoUnit.DAYS));
        final LedgerAttestation flagged = attestation(bad.id, AttestationVerdict.FLAGGED,
                now.minus(3, ChronoUnit.DAYS));

        final TrustScoreComputer.ActorScore score = computer.compute(
                List.of(clean1, clean2, bad),
                Map.of(bad.id, List.of(flagged)),
                now);

        assertThat(score.decisionCount()).isEqualTo(3);
        assertThat(score.overturnedCount()).isEqualTo(1);
        assertThat(score.trustScore()).isGreaterThan(0.0);
        assertThat(score.trustScore()).isLessThan(0.5); // net negative
    }

    // ── Recency weighting ─────────────────────────────────────────────────────

    @Test
    void recentPositiveAttestation_outweighsOldNegative() {
        // Recent positive: recencyWeight ≈ 1.0 → α increases by ~1.0
        // Old negative (180 days, halfLife=90): recencyWeight = 2^(-2) = 0.25 → β increases by 0.25
        final TestLedgerEntry d1 = decision("alice", now.minus(1, ChronoUnit.DAYS));
        final LedgerAttestation recentSound = attestation(
                d1.id, AttestationVerdict.SOUND, now.minus(1, ChronoUnit.DAYS));

        final TestLedgerEntry d2 = decision("alice", now.minus(180, ChronoUnit.DAYS));
        final LedgerAttestation oldFlagged = attestation(
                d2.id, AttestationVerdict.FLAGGED, now.minus(180, ChronoUnit.DAYS));

        final TrustScoreComputer.ActorScore score = computer.compute(
                List.of(d1, d2),
                Map.of(d1.id, List.of(recentSound), d2.id, List.of(oldFlagged)),
                now);

        assertThat(score.trustScore()).isGreaterThan(0.5);
    }

    @Test
    void attestationAgeUsed_notDecisionAge() {
        // One old decision (365 days ago) with a recent attestation (1 day ago).
        // The attestation's own occurredAt is used for recency — weight ≈ 1.0, not ~0.
        final TestLedgerEntry old = decision("alice", now.minus(365, ChronoUnit.DAYS));
        final LedgerAttestation recentOnOldDecision = attestation(
                old.id, AttestationVerdict.SOUND, now.minus(1, ChronoUnit.DAYS));

        final TrustScoreComputer.ActorScore usingAttestationAge = computer.compute(
                List.of(old), Map.of(old.id, List.of(recentOnOldDecision)), now);

        // If decision age were used (365 days, halfLife=90): weight = 2^(-4) ≈ 0.0625
        // → α ≈ 1.0625, score ≈ 0.516 (barely above neutral)
        // If attestation age used (1 day): weight ≈ 1.0
        // → α ≈ 2.0, score ≈ 0.667 (clearly above neutral)
        assertThat(usingAttestationAge.trustScore()).isGreaterThan(0.6);
    }

    @Test
    void shortHalfLife_oldAttestationHasMinimalWeight() {
        final TrustScoreComputer shortHalfLife = new TrustScoreComputer(30);

        final TestLedgerEntry d1 = decision("alice", now.minus(1, ChronoUnit.DAYS));
        final LedgerAttestation recentSound = attestation(
                d1.id, AttestationVerdict.SOUND, now.minus(1, ChronoUnit.DAYS));

        final TestLedgerEntry d2 = decision("alice", now.minus(365, ChronoUnit.DAYS));
        final LedgerAttestation oldFlagged = attestation(
                d2.id, AttestationVerdict.FLAGGED, now.minus(365, ChronoUnit.DAYS));

        final TrustScoreComputer.ActorScore score = shortHalfLife.compute(
                List.of(d1, d2),
                Map.of(d1.id, List.of(recentSound), d2.id, List.of(oldFlagged)),
                now);

        assertThat(score.trustScore()).isGreaterThan(0.5);
    }

    // ── Score bounds ──────────────────────────────────────────────────────────

    @Test
    void score_alwaysWithinZeroToOne() {
        final TestLedgerEntry d = decision("alice", now.minus(1, ChronoUnit.DAYS));
        final List<LedgerAttestation> many = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            many.add(attestation(d.id, AttestationVerdict.FLAGGED));
        }

        final TrustScoreComputer.ActorScore score = computer.compute(
                List.of(d), Map.of(d.id, many), now);

        assertThat(score.trustScore()).isGreaterThanOrEqualTo(0.0);
        assertThat(score.trustScore()).isLessThanOrEqualTo(1.0);
    }

    // ── Confidence weighting ──────────────────────────────────────────────────

    @Test
    void confidence_scales_alpha_contribution() {
        // actor-full: confidence=1.0 → full recency weight
        // actor-half: confidence=0.5 → half recency weight
        // actor-full must score higher
        final TestLedgerEntry full = decision("actor-full", now);
        final TestLedgerEntry half = decision("actor-half", now);
        final LedgerAttestation att1 = attestation(full.id, AttestationVerdict.SOUND, 1.0);
        final LedgerAttestation att2 = attestation(half.id, AttestationVerdict.SOUND, 0.5);

        final TrustScoreComputer.ActorScore scoreA = computer.compute(
                List.of(full), Map.of(full.id, List.of(att1)), now);
        final TrustScoreComputer.ActorScore scoreB = computer.compute(
                List.of(half), Map.of(half.id, List.of(att2)), now);

        assertThat(scoreA.trustScore()).isGreaterThan(scoreB.trustScore());
    }

    @Test
    void confidence_zero_contributes_nothing() {
        // confidence=0.0 → weight=0.0 → prior only → α=1, β=1 → score=0.5
        final TestLedgerEntry entry = decision("actor", now);
        final LedgerAttestation att = attestation(entry.id, AttestationVerdict.SOUND, 0.0);

        final TrustScoreComputer.ActorScore score = computer.compute(
                List.of(entry), Map.of(entry.id, List.of(att)), now);

        assertThat(score.trustScore()).isCloseTo(0.5, within(0.001));
    }

    @Test
    void confidence_one_matches_previous_unweighted_behaviour() {
        // Prior Beta(1,1) + 1 positive at full weight → α=2, β=1 → 2/3 ≈ 0.667
        final TestLedgerEntry entry = decision("actor", now);
        final LedgerAttestation att = attestation(entry.id, AttestationVerdict.SOUND, 1.0);

        final TrustScoreComputer.ActorScore score = computer.compute(
                List.of(entry), Map.of(entry.id, List.of(att)), now);

        assertThat(score.trustScore()).isCloseTo(2.0 / 3.0, within(0.001));
    }

    @Test
    void confidence_scales_beta_contribution() {
        // actor-full: FLAGGED confidence=1.0 → β += 1.0 → score = 1/3 ≈ 0.333
        // actor-half: FLAGGED confidence=0.5 → β += 0.5 → score = 1/2.5 = 0.4
        // actor-full should score lower (more negative contribution)
        final TestLedgerEntry full = decision("actor-full", now);
        final TestLedgerEntry half = decision("actor-half", now);
        final LedgerAttestation att1 = attestation(full.id, AttestationVerdict.FLAGGED, 1.0);
        final LedgerAttestation att2 = attestation(half.id, AttestationVerdict.FLAGGED, 0.5);

        final TrustScoreComputer.ActorScore scoreA = computer.compute(
                List.of(full), Map.of(full.id, List.of(att1)), now);
        final TrustScoreComputer.ActorScore scoreB = computer.compute(
                List.of(half), Map.of(half.id, List.of(att2)), now);

        assertThat(scoreA.trustScore()).isLessThan(scoreB.trustScore());
    }

    @Test
    void confidence_negative_clamped_to_zero() {
        // confidence=-1.0 → clamp → 0.0 → weight=0 → prior only → score=0.5
        final TestLedgerEntry entry = decision("actor", now);
        final LedgerAttestation att = attestation(entry.id, AttestationVerdict.FLAGGED, -1.0);

        final TrustScoreComputer.ActorScore score = computer.compute(
                List.of(entry), Map.of(entry.id, List.of(att)), now);

        assertThat(score.trustScore()).isCloseTo(0.5, within(0.001));
    }

    @Test
    void zero_confidence_negative_does_not_overturn() {
        // confidence=0.0 → weight=0 → β unchanged → overturnedCount must stay 0
        // attestationNegative is a raw verdict count — it still records the verdict
        final TestLedgerEntry entry = decision("actor", now);
        final LedgerAttestation att = attestation(entry.id, AttestationVerdict.FLAGGED, 0.0);

        final TrustScoreComputer.ActorScore score = computer.compute(
                List.of(entry), Map.of(entry.id, List.of(att)), now);

        assertThat(score.overturnedCount()).isEqualTo(0);
        assertThat(score.attestationNegative()).isEqualTo(1);
        assertThat(score.trustScore()).isCloseTo(0.5, within(0.001));
    }

    @Test
    void confidence_above_one_is_clamped_to_one() {
        final TestLedgerEntry entry = decision("actor", now);
        final LedgerAttestation over = attestation(entry.id, AttestationVerdict.SOUND, 2.0);
        final LedgerAttestation full = attestation(entry.id, AttestationVerdict.SOUND, 1.0);

        final TrustScoreComputer.ActorScore scoreOver = computer.compute(
                List.of(entry), Map.of(entry.id, List.of(over)), now);
        final TestLedgerEntry entry2 = decision("actor2", now);
        final TrustScoreComputer.ActorScore scoreFull = computer.compute(
                List.of(entry2), Map.of(entry2.id, List.of(full)), now);

        assertThat(scoreOver.trustScore()).isCloseTo(scoreFull.trustScore(), within(0.001));
    }

    // ── Default half-life ─────────────────────────────────────────────────────

    @Test
    void zeroHalfLifeDays_defaultsTo90() {
        final TrustScoreComputer computer0 = new TrustScoreComputer(0);
        final TrustScoreComputer computer90 = new TrustScoreComputer(90);

        final TestLedgerEntry d = decision("alice", now.minus(30, ChronoUnit.DAYS));
        final LedgerAttestation a = attestation(
                d.id, AttestationVerdict.SOUND, now.minus(30, ChronoUnit.DAYS));

        final TrustScoreComputer.ActorScore s0 = computer0.compute(
                List.of(d), Map.of(d.id, List.of(a)), now);
        final TrustScoreComputer.ActorScore s90 = computer90.compute(
                List.of(d), Map.of(d.id, List.of(a)), now);

        assertThat(s0.trustScore()).isCloseTo(s90.trustScore(), within(0.001));
    }
}
