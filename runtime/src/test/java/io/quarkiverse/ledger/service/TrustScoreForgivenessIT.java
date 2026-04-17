package io.quarkiverse.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.Test;

import io.quarkiverse.ledger.runtime.model.ActorTrustScore;
import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.model.AttestationVerdict;
import io.quarkiverse.ledger.runtime.model.LedgerAttestation;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.service.TrustScoreJob;
import io.quarkiverse.ledger.service.supplement.TestEntry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

/**
 * End-to-end integration test for the forgiveness mechanism.
 *
 * <p>
 * Runs the full {@link TrustScoreJob} pipeline against an H2 database populated with
 * real ledger entries and attestations, verifying that:
 * <ul>
 * <li>An actor with an old flagged decision recovers a high trust score (old failures forgiven)</li>
 * <li>A clean actor's score is unaffected by forgiveness</li>
 * <li>A repeat offender receives less forgiveness than a one-off offender</li>
 * </ul>
 *
 * <p>
 * Uses the {@code forgiveness-test} Quarkus profile which enables trust scoring and
 * forgiveness via {@code %forgiveness-test.*} keys in {@code application.properties}.
 */
@QuarkusTest
@TestProfile(TrustScoreForgivenessIT.ForgivenessProfile.class)
class TrustScoreForgivenessIT {

    public static class ForgivenessProfile implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "forgiveness-test";
        }
    }

    @Inject
    TrustScoreJob trustScoreJob;

    // ── happy path: forgiveness raises score for actor with old failure ──────

    @Test
    @Transactional
    void forgiveness_raisesScore_forActorWithOldFailure() {
        final String actorId = "agent-forgiveness-" + UUID.randomUUID();

        // One flagged decision 45 days ago (recencyForgiveness = 2^(-45/30) ≈ 0.35)
        seedDecision(actorId, now().minus(45, ChronoUnit.DAYS), AttestationVerdict.FLAGGED);

        // Five clean decisions in the past week
        seedDecision(actorId, now().minus(6, ChronoUnit.DAYS), null);
        seedDecision(actorId, now().minus(5, ChronoUnit.DAYS), null);
        seedDecision(actorId, now().minus(4, ChronoUnit.DAYS), null);
        seedDecision(actorId, now().minus(3, ChronoUnit.DAYS), null);
        seedDecision(actorId, now().minus(1, ChronoUnit.DAYS), null);

        trustScoreJob.runComputation();

        final ActorTrustScore score = ActorTrustScore
                .<ActorTrustScore> find("actorId", actorId).firstResult();
        assertThat(score).isNotNull();
        // With forgiveness: old failure partially forgiven, recent clean history dominates
        assertThat(score.trustScore).isGreaterThan(0.7);
        assertThat(score.decisionCount).isEqualTo(6);
        assertThat(score.overturnedCount).isEqualTo(1);
    }

    // ── happy path: clean actor unaffected by forgiveness ───────────────────

    @Test
    @Transactional
    void forgiveness_cleanActor_scoreUnchanged() {
        final String actorId = "agent-clean-" + UUID.randomUUID();

        seedDecision(actorId, now().minus(3, ChronoUnit.DAYS), null);
        seedDecision(actorId, now().minus(2, ChronoUnit.DAYS), null);
        seedDecision(actorId, now().minus(1, ChronoUnit.DAYS), null);

        trustScoreJob.runComputation();

        final ActorTrustScore score = ActorTrustScore
                .<ActorTrustScore> find("actorId", actorId).firstResult();
        assertThat(score).isNotNull();
        assertThat(score.trustScore).isCloseTo(1.0, within(0.01));
    }

    // ── happy path: repeat offender receives less forgiveness ────────────────

    @Test
    @Transactional
    void forgiveness_repeatOffender_lowerScoreThanOneOff() {
        final String oneOffActor = "agent-oneoff-" + UUID.randomUUID();
        final String repeatActor = "agent-repeat-" + UUID.randomUUID();
        final Instant failureTime = now().minus(20, ChronoUnit.DAYS);

        // One-off: 1 negative decision (below threshold=3)
        seedDecision(oneOffActor, failureTime, AttestationVerdict.FLAGGED);
        seedDecision(oneOffActor, now().minus(2, ChronoUnit.DAYS), null);

        // Repeat offender: 5 negative decisions (above threshold=3)
        for (int i = 0; i < 5; i++) {
            seedDecision(repeatActor, failureTime.minusSeconds(i * 60L), AttestationVerdict.FLAGGED);
        }
        seedDecision(repeatActor, now().minus(2, ChronoUnit.DAYS), null);

        trustScoreJob.runComputation();

        final double oneOffScore = ActorTrustScore
                .<ActorTrustScore> find("actorId", oneOffActor).firstResult().trustScore;
        final double repeatScore = ActorTrustScore
                .<ActorTrustScore> find("actorId", repeatActor).firstResult().trustScore;

        assertThat(oneOffScore).isGreaterThan(repeatScore);
    }

    // ── fixture ───────────────────────────────────────────────────────────────

    private void seedDecision(final String actorId, final Instant occurredAt,
            final AttestationVerdict verdictOrNull) {
        final TestEntry entry = new TestEntry();
        entry.subjectId = UUID.randomUUID();
        entry.sequenceNumber = 1;
        entry.entryType = LedgerEntryType.EVENT;
        entry.actorId = actorId;
        entry.actorType = ActorType.AGENT;
        entry.actorRole = "Classifier";
        entry.occurredAt = occurredAt;
        entry.persist();

        if (verdictOrNull != null) {
            final LedgerAttestation att = new LedgerAttestation();
            att.id = UUID.randomUUID();
            att.ledgerEntryId = entry.id;
            att.subjectId = entry.subjectId;
            att.attestorId = "compliance-bot";
            att.attestorType = ActorType.AGENT;
            att.verdict = verdictOrNull;
            att.confidence = 0.9;
            att.occurredAt = occurredAt.plusSeconds(60);
            att.persist();
        }
    }

    private Instant now() {
        return Instant.now();
    }
}
