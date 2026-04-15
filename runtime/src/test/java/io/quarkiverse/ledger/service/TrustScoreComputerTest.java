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
}
