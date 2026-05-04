package io.casehub.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.ActorTrustScore.ScoreType;
import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.CapabilityTag;
import io.casehub.ledger.runtime.model.ActorTrustScore;
import io.casehub.ledger.runtime.repository.ActorTrustScoreRepository;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;
import io.casehub.ledger.runtime.service.TrustGateService;
import io.casehub.ledger.runtime.service.TrustScoreJob;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * End-to-end integration tests for multi-dimensional trust scoring (#62).
 *
 * <p>Covers: happy path, correctness/isolation, robustness, and backward compatibility.
 */
@QuarkusTest
@TestProfile(TrustScoreIT.TrustScoreTestProfile.class)
class TrustScoreDimensionIT {

    @Inject
    TrustScoreJob trustScoreJob;

    @Inject
    TrustGateService trustGateService;

    @Inject
    LedgerEntryRepository repo;

    @Inject
    ActorTrustScoreRepository trustRepo;

    @Inject
    EntityManager em;

    // ── Happy path: dimension attestations produce DIMENSION rows ─────────────

    @Test
    @Transactional
    void dimensionAttestations_producesDimensionRows() {
        final String actorId = "agent-dim-" + UUID.randomUUID();
        final Instant now = Instant.now();

        seedDimension(actorId, now.minus(1, ChronoUnit.DAYS), "review-thoroughness", 0.9);
        seedDimension(actorId, now.minus(2, ChronoUnit.DAYS), "review-thoroughness", 0.7);
        seedDimension(actorId, now.minus(1, ChronoUnit.DAYS), "false-positive-rate", 0.1);

        trustScoreJob.runComputation();

        final List<ActorTrustScore> dimScores = trustRepo.findByActorIdAndScoreType(actorId, ScoreType.DIMENSION);
        assertThat(dimScores).hasSize(2);

        final ActorTrustScore thoroughness = dimScores.stream()
                .filter(s -> "review-thoroughness".equals(s.scopeKey)).findFirst().orElseThrow();
        assertThat(thoroughness.trustScore).isGreaterThan(0.7);
        assertThat(thoroughness.trustScore).isLessThan(1.0);

        final ActorTrustScore fpr = dimScores.stream()
                .filter(s -> "false-positive-rate".equals(s.scopeKey)).findFirst().orElseThrow();
        assertThat(fpr.trustScore).isCloseTo(0.1, within(0.05));
    }

    // ── Happy path: TrustGateService.dimensionScores() returns all dimensions ─

    @Test
    @Transactional
    void trustGateService_dimensionScores_returnsAll() {
        final String actorId = "agent-dimmap-" + UUID.randomUUID();
        final Instant now = Instant.now();

        seedDimension(actorId, now.minus(1, ChronoUnit.DAYS), "thoroughness", 0.8);
        seedDimension(actorId, now.minus(1, ChronoUnit.DAYS), "accuracy", 0.6);

        trustScoreJob.runComputation();

        final Map<String, Double> scores = trustGateService.dimensionScores(actorId);
        assertThat(scores).containsKey("thoroughness");
        assertThat(scores).containsKey("accuracy");
        assertThat(scores.get("thoroughness")).isCloseTo(0.8, within(0.05));
        assertThat(scores.get("accuracy")).isCloseTo(0.6, within(0.05));
    }

    // ── Happy path: TrustGateService.dimensionScore() returns specific dimension

    @Test
    @Transactional
    void trustGateService_dimensionScore_returnsSpecificDimension() {
        final String actorId = "agent-dimone-" + UUID.randomUUID();
        final Instant now = Instant.now();

        seedDimension(actorId, now.minus(1, ChronoUnit.DAYS), "thoroughness", 0.85);

        trustScoreJob.runComputation();

        assertThat(trustGateService.dimensionScore(actorId, "thoroughness")).isPresent();
        assertThat(trustGateService.dimensionScore(actorId, "thoroughness").get())
                .isCloseTo(0.85, within(0.05));
    }

    // ── Correctness: decay — older attestation has lower weight ───────────────

    @Test
    @Transactional
    void dimensionScore_olderAttestationDecays() {
        final String actorId = "agent-decay-" + UUID.randomUUID();
        final Instant now = Instant.now();

        seedDimension(actorId, now.minus(1, ChronoUnit.DAYS), "thoroughness", 1.0);
        seedDimension(actorId, now.minus(365, ChronoUnit.DAYS), "thoroughness", 0.0);

        trustScoreJob.runComputation();

        final var score = trustGateService.dimensionScore(actorId, "thoroughness");
        assertThat(score).isPresent();
        assertThat(score.get()).isGreaterThan(0.5);
    }

    // ── Correctness: separate dimensions are isolated ─────────────────────────

    @Test
    @Transactional
    void dimensionScores_isolatedFromEachOther() {
        final String actorId = "agent-iso-" + UUID.randomUUID();
        final Instant now = Instant.now();

        seedDimension(actorId, now.minus(1, ChronoUnit.DAYS), "thoroughness", 0.9);
        seedDimension(actorId, now.minus(1, ChronoUnit.DAYS), "false-positive-rate", 0.05);

        trustScoreJob.runComputation();

        final var thoroughness = trustGateService.dimensionScore(actorId, "thoroughness").orElseThrow();
        final var fpr = trustGateService.dimensionScore(actorId, "false-positive-rate").orElseThrow();

        assertThat(thoroughness).isGreaterThan(0.8);
        assertThat(fpr).isLessThan(0.2);
        assertThat(thoroughness).isNotCloseTo(fpr, within(0.5));
    }

    // ── Correctness: GLOBAL and CAPABILITY rows unaffected ────────────────────

    @Test
    @Transactional
    void dimensionPass_doesNotAffectGlobalOrCapabilityRows() {
        final String actorId = "agent-compat-" + UUID.randomUUID();
        final Instant now = Instant.now();

        LedgerTestFixtures.seedDecision(actorId, now.minus(1, ChronoUnit.DAYS),
                AttestationVerdict.SOUND, now.minus(1, ChronoUnit.DAYS).plusSeconds(60),
                CapabilityTag.GLOBAL, repo, em);

        LedgerTestFixtures.seedDecision(actorId, now.minus(2, ChronoUnit.DAYS),
                AttestationVerdict.SOUND, now.minus(2, ChronoUnit.DAYS).plusSeconds(60),
                "security-review", repo, em);

        seedDimension(actorId, now.minus(1, ChronoUnit.DAYS), "thoroughness", 0.7);

        trustScoreJob.runComputation();

        final var global = trustRepo.findByActorId(actorId);
        assertThat(global).isPresent();
        assertThat(global.get().scoreType).isEqualTo(ScoreType.GLOBAL);

        final var capability = trustRepo.findByActorIdAndTypeAndKey(actorId, ScoreType.CAPABILITY, "security-review");
        assertThat(capability).isPresent();

        final var dimension = trustRepo.findByActorIdAndTypeAndKey(actorId, ScoreType.DIMENSION, "thoroughness");
        assertThat(dimension).isPresent();
        assertThat(dimension.get().trustScore).isCloseTo(0.7, within(0.05));
    }

    // ── Robustness: actor with no dimension attestations gets no DIMENSION rows

    @Test
    @Transactional
    void nonemptyActor_noDimensionAttestations_noDimensionRows() {
        final String actorId = "agent-nodim-" + UUID.randomUUID();
        final Instant now = Instant.now();

        LedgerTestFixtures.seedDecision(actorId, now.minus(1, ChronoUnit.DAYS),
                AttestationVerdict.SOUND, repo, em);

        trustScoreJob.runComputation();

        assertThat(trustRepo.findByActorIdAndScoreType(actorId, ScoreType.DIMENSION)).isEmpty();
        assertThat(trustRepo.findByActorId(actorId)).isPresent();
    }

    // ── Robustness: attestation with trustDimension but null dimensionScore excluded

    @Test
    @Transactional
    void dimensionAttestation_nullDimensionScore_excludedFromComputation() {
        final String actorId = "agent-nullscore-" + UUID.randomUUID();
        final Instant now = Instant.now();

        LedgerTestFixtures.seedDecision(actorId, now.minus(1, ChronoUnit.DAYS),
                AttestationVerdict.SOUND, now.minus(1, ChronoUnit.DAYS).plusSeconds(60),
                CapabilityTag.GLOBAL, repo, em);

        // Persist a dimension attestation with null dimensionScore via em
        final List<io.casehub.ledger.runtime.model.LedgerEntry> entries = repo.findAllEvents();
        final var myEntry = entries.stream()
                .filter(e -> actorId.equals(e.actorId)).findFirst().orElseThrow();

        final io.casehub.ledger.runtime.model.LedgerAttestation badAtt =
                new io.casehub.ledger.runtime.model.LedgerAttestation();
        badAtt.id = UUID.randomUUID();
        badAtt.ledgerEntryId = myEntry.id;
        badAtt.subjectId = myEntry.subjectId;
        badAtt.attestorId = "bad-peer";
        badAtt.attestorType = io.casehub.ledger.api.model.ActorType.AGENT;
        badAtt.verdict = AttestationVerdict.SOUND;
        badAtt.confidence = 1.0;
        badAtt.capabilityTag = CapabilityTag.GLOBAL;
        badAtt.trustDimension = "thoroughness";
        badAtt.dimensionScore = null;
        badAtt.occurredAt = now;
        em.persist(badAtt);

        trustScoreJob.runComputation();

        assertThat(trustRepo.findByActorIdAndScoreType(actorId, ScoreType.DIMENSION)).isEmpty();
    }

    // ── Robustness: unknown dimension queried → empty ─────────────────────────

    @Test
    @Transactional
    void trustGateService_unknownDimension_returnsEmpty() {
        final String actorId = "agent-unknowndim-" + UUID.randomUUID();
        final Instant now = Instant.now();

        seedDimension(actorId, now.minus(1, ChronoUnit.DAYS), "thoroughness", 0.8);
        trustScoreJob.runComputation();

        assertThat(trustGateService.dimensionScore(actorId, "nonexistent-dimension")).isEmpty();
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private void seedDimension(final String actorId, final Instant decisionTime,
            final String dimension, final double dimensionScore) {
        LedgerTestFixtures.seedDecisionWithDimension(actorId, decisionTime,
                dimension, dimensionScore, CapabilityTag.GLOBAL, repo, em);
    }
}
