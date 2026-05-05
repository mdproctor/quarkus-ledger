package io.casehub.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.ActorTrustScore.ScoreType;
import io.casehub.ledger.api.model.ActorType;
import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.runtime.model.ActorTrustScore;
import io.casehub.ledger.runtime.model.LedgerAttestation;
import io.casehub.ledger.runtime.repository.ActorTrustScoreRepository;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;
import io.casehub.ledger.runtime.service.TrustScoreJob;
import io.casehub.ledger.service.supplement.TestEntry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Integration tests for AttestationAggregator wired into TrustScoreJob.
 *
 * <p>Runs under the trust-score-test profile (isolated DB, scheduler disabled).
 * Tests use WEIGHTED_MAJORITY (the default).
 */
@QuarkusTest
@TestProfile(TrustScoreIT.TrustScoreTestProfile.class)
class TrustScoreAggregationIT {

    @Inject TrustScoreJob trustScoreJob;
    @Inject LedgerEntryRepository repo;
    @Inject ActorTrustScoreRepository trustRepo;
    @Inject EntityManager em;

    // ── Happy path: majority SOUND attestations produce higher score than minority FLAGGED ──

    @Test
    @Transactional
    void weightedMajority_twoSoundOneFlagged_scoreTiltsSoundward() {
        final String actorId = "agg-actor-" + UUID.randomUUID();
        final TestEntry decision = decision(actorId);

        // 2 SOUND (high confidence) + 1 FLAGGED (low confidence) on same entry
        attest(decision.id, decision.subjectId, AttestationVerdict.SOUND, 0.9, "*");
        attest(decision.id, decision.subjectId, AttestationVerdict.SOUND, 0.9, "*");
        attest(decision.id, decision.subjectId, AttestationVerdict.FLAGGED, 0.2, "*");

        trustScoreJob.runComputation();

        final ActorTrustScore globalScore = trustRepo.findByActorId(actorId).orElseThrow();
        // WEIGHTED_MAJORITY collapses to one SOUND attestation — score > 0.5
        assertThat(globalScore.trustScore).isGreaterThan(0.5);
    }

    // ── Correctness: single attestation is unchanged ──────────────────────────

    @Test
    @Transactional
    void singleAttestation_behaviorUnchanged() {
        final String actorId = "agg-single-" + UUID.randomUUID();
        final TestEntry decision = decision(actorId);

        attest(decision.id, decision.subjectId, AttestationVerdict.SOUND, 0.8, "*");

        trustScoreJob.runComputation();

        final ActorTrustScore score = trustRepo.findByActorId(actorId).orElseThrow();
        assertThat(score.trustScore).isGreaterThan(0.5);
    }

    // ── Correctness: capability tags aggregated independently ─────────────────

    @Test
    @Transactional
    void multipleCapabilityTags_aggregatedSeparately() {
        final String actorId = "agg-cap-" + UUID.randomUUID();
        final TestEntry decision = decision(actorId);

        // code-review: 2 SOUND (aggregated → SOUND)
        attest(decision.id, decision.subjectId, AttestationVerdict.SOUND, 0.9, "code-review");
        attest(decision.id, decision.subjectId, AttestationVerdict.SOUND, 0.8, "code-review");
        // security-review: 1 FLAGGED (aggregated → FLAGGED)
        attest(decision.id, decision.subjectId, AttestationVerdict.FLAGGED, 0.9, "security-review");

        trustScoreJob.runComputation();

        final ActorTrustScore codeReviewScore = trustRepo.findByActorIdAndTypeAndKey(actorId, ScoreType.CAPABILITY, "code-review").orElseThrow();
        final ActorTrustScore securityScore = trustRepo.findByActorIdAndTypeAndKey(actorId, ScoreType.CAPABILITY, "security-review").orElseThrow();

        assertThat(codeReviewScore.trustScore).isGreaterThan(0.5);
        assertThat(securityScore.trustScore).isLessThan(0.5);
    }

    // ── Robustness: no attestations — entry contributes nothing ──────────────

    @Test
    @Transactional
    void noAttestations_scoreIsNeutral() {
        final String actorId = "agg-none-" + UUID.randomUUID();
        decision(actorId);

        trustScoreJob.runComputation();

        final var scoreOpt = trustRepo.findByActorId(actorId);
        // No attestations → neutral prior (0.5) or no score at all
        scoreOpt.ifPresent(s -> assertThat(s.trustScore).isCloseTo(0.5, within(0.01)));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private TestEntry decision(final String actorId) {
        final TestEntry e = new TestEntry();
        e.subjectId = UUID.randomUUID();
        e.sequenceNumber = 1;
        e.entryType = LedgerEntryType.EVENT;
        e.actorId = actorId;
        e.actorType = ActorType.AGENT;
        e.actorRole = "Reviewer";
        e.occurredAt = Instant.now();
        return (TestEntry) repo.save(e);
    }

    private void attest(final UUID entryId, final UUID subjectId,
            final AttestationVerdict verdict, final double confidence,
            final String capabilityTag) {
        final LedgerAttestation a = new LedgerAttestation();
        a.id = UUID.randomUUID();
        a.ledgerEntryId = entryId;
        a.subjectId = subjectId;
        a.attestorId = "attestor-" + UUID.randomUUID();
        a.attestorType = ActorType.AGENT;
        a.verdict = verdict;
        a.confidence = confidence;
        a.capabilityTag = capabilityTag;
        a.occurredAt = Instant.now();
        em.persist(a);
    }
}
