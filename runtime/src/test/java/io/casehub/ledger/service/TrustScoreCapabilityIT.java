package io.casehub.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
 * End-to-end integration tests for capability-scoped trust scoring (issue #61).
 *
 * <p>Covers: happy path, correctness/isolation, robustness, and backward compatibility.
 */
@QuarkusTest
@TestProfile(TrustScoreIT.TrustScoreTestProfile.class)
class TrustScoreCapabilityIT {

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

    // ── Happy path: capability rows created per distinct tag ──────────────────

    @Test
    @Transactional
    void capabilityTaggedAttestations_createCapabilityRows() {
        final String actorId = "agent-capability-" + UUID.randomUUID();
        final Instant now = Instant.now();

        seedWithCapability(actorId, now.minus(1, ChronoUnit.DAYS), AttestationVerdict.SOUND, "security-review");
        seedWithCapability(actorId, now.minus(2, ChronoUnit.DAYS), AttestationVerdict.SOUND, "security-review");
        seedWithCapability(actorId, now.minus(3, ChronoUnit.DAYS), AttestationVerdict.FLAGGED, "style-review");

        trustScoreJob.runComputation();

        final List<ActorTrustScore> capScores = trustRepo.findByActorIdAndScoreType(actorId, ScoreType.CAPABILITY);
        assertThat(capScores).hasSize(2);

        final ActorTrustScore secScore = capScores.stream()
                .filter(s -> "security-review".equals(s.scopeKey)).findFirst().orElseThrow();
        assertThat(secScore.trustScore).isGreaterThan(0.6);

        final ActorTrustScore styleScore = capScores.stream()
                .filter(s -> "style-review".equals(s.scopeKey)).findFirst().orElseThrow();
        assertThat(styleScore.trustScore).isLessThan(0.5);
    }

    // ── Happy path: global row uses all attestations (Option B default) ───────

    @Test
    @Transactional
    void globalRow_usesAllAttestations_optionB() {
        final String actorId = "agent-global-" + UUID.randomUUID();
        final Instant now = Instant.now();

        seedWithCapability(actorId, now.minus(1, ChronoUnit.DAYS), AttestationVerdict.SOUND, "security-review");
        seedWithCapability(actorId, now.minus(2, ChronoUnit.DAYS), AttestationVerdict.SOUND, "security-review");
        seedWithCapability(actorId, now.minus(3, ChronoUnit.DAYS), AttestationVerdict.FLAGGED, "style-review");

        trustScoreJob.runComputation();

        final ActorTrustScore global = trustRepo.findByActorId(actorId).orElseThrow();
        final List<ActorTrustScore> caps = trustRepo.findByActorIdAndScoreType(actorId, ScoreType.CAPABILITY);

        final double secScore = caps.stream().filter(s -> "security-review".equals(s.scopeKey))
                .findFirst().orElseThrow().trustScore;
        final double styleScore = caps.stream().filter(s -> "style-review".equals(s.scopeKey))
                .findFirst().orElseThrow().trustScore;

        // Global must be between style (low) and security (high) scores since it includes all
        assertThat(global.trustScore).isBetween(styleScore, secScore);
        assertThat(global.scoreType).isEqualTo(ScoreType.GLOBAL);
    }

    // ── Correctness: capability Beta is isolated to its own tag ───────────────

    @Test
    @Transactional
    void capabilityScore_isolatedToItsTag() {
        final String actorId = "agent-isolated-" + UUID.randomUUID();
        final Instant now = Instant.now();

        seedWithCapability(actorId, now.minus(1, ChronoUnit.DAYS), AttestationVerdict.SOUND, "security-review");
        seedWithCapability(actorId, now.minus(2, ChronoUnit.DAYS), AttestationVerdict.SOUND, "security-review");
        seedWithCapability(actorId, now.minus(3, ChronoUnit.DAYS), AttestationVerdict.FLAGGED, "style-review");

        trustScoreJob.runComputation();

        final var secScore = trustRepo.findByActorIdAndTypeAndKey(actorId, ScoreType.CAPABILITY, "security-review")
                .orElseThrow();
        final var styleScore = trustRepo.findByActorIdAndTypeAndKey(actorId, ScoreType.CAPABILITY, "style-review")
                .orElseThrow();

        assertThat(secScore.trustScore).isCloseTo(0.75, within(0.1));
        assertThat(styleScore.trustScore).isLessThan(0.5);
        assertThat(secScore.trustScore).isGreaterThan(styleScore.trustScore);
    }

    // ── Correctness: actor with only global ("*") attestations gets no capability rows

    @Test
    @Transactional
    void globalOnlyActor_noCapabilityRows() {
        final String actorId = "agent-star-only-" + UUID.randomUUID();
        final Instant now = Instant.now();

        seedWithCapability(actorId, now.minus(1, ChronoUnit.DAYS), AttestationVerdict.SOUND, CapabilityTag.GLOBAL);
        seedWithCapability(actorId, now.minus(2, ChronoUnit.DAYS), AttestationVerdict.SOUND, CapabilityTag.GLOBAL);

        trustScoreJob.runComputation();

        final List<ActorTrustScore> capScores = trustRepo.findByActorIdAndScoreType(actorId, ScoreType.CAPABILITY);
        assertThat(capScores).isEmpty();

        final ActorTrustScore global = trustRepo.findByActorId(actorId).orElseThrow();
        assertThat(global.trustScore).isGreaterThan(0.5);
    }

    // ── Correctness: TrustGateService uses capability score ───────────────────

    @Test
    @Transactional
    void trustGateService_usesCapabilityScore() {
        final String actorId = "agent-gate-" + UUID.randomUUID();
        final Instant now = Instant.now();

        seedWithCapability(actorId, now.minus(1, ChronoUnit.DAYS), AttestationVerdict.SOUND, "security-review");
        seedWithCapability(actorId, now.minus(2, ChronoUnit.DAYS), AttestationVerdict.SOUND, "security-review");
        seedWithCapability(actorId, now.minus(3, ChronoUnit.DAYS), AttestationVerdict.SOUND, "security-review");
        seedWithCapability(actorId, now.minus(4, ChronoUnit.DAYS), AttestationVerdict.FLAGGED, "style-review");

        trustScoreJob.runComputation();

        assertThat(trustGateService.meetsThreshold(actorId, "security-review", 0.7)).isTrue();
        assertThat(trustGateService.meetsThreshold(actorId, "style-review", 0.6)).isFalse();
    }

    // ── Correctness: unknown tag falls back to global ─────────────────────────

    @Test
    @Transactional
    void trustGateService_unknownTag_fallsBackToGlobal() {
        final String actorId = "agent-fallback-" + UUID.randomUUID();
        final Instant now = Instant.now();

        seedWithCapability(actorId, now.minus(1, ChronoUnit.DAYS), AttestationVerdict.SOUND, "security-review");
        seedWithCapability(actorId, now.minus(2, ChronoUnit.DAYS), AttestationVerdict.SOUND, "security-review");

        trustScoreJob.runComputation();

        final ActorTrustScore global = trustRepo.findByActorId(actorId).orElseThrow();

        final boolean result = trustGateService.meetsThreshold(actorId, "nonexistent-capability",
                global.trustScore - 0.1);
        assertThat(result).isTrue();
    }

    // ── Robustness: no attestations → GLOBAL = 0.5; no CAPABILITY rows ────────

    @Test
    @Transactional
    void noAttestations_globalNeutral_noCapabilityRows() {
        final String actorId = "agent-none-" + UUID.randomUUID();
        final Instant now = Instant.now();

        seedWithCapability(actorId, now.minus(1, ChronoUnit.DAYS), null, CapabilityTag.GLOBAL);

        trustScoreJob.runComputation();

        final ActorTrustScore global = trustRepo.findByActorId(actorId).orElseThrow();
        assertThat(global.trustScore).isCloseTo(0.5, within(0.01));

        final List<ActorTrustScore> caps = trustRepo.findByActorIdAndScoreType(actorId, ScoreType.CAPABILITY);
        assertThat(caps).isEmpty();
    }

    // ── Backward compatibility: pre-B1 actors (all "*") unchanged ────────────

    @Test
    @Transactional
    void preB1Actor_allGlobalAttestations_globalUnchanged() {
        final String actorId = "agent-legacy-" + UUID.randomUUID();
        final Instant now = Instant.now();

        seedWithCapability(actorId, now.minus(1, ChronoUnit.DAYS), AttestationVerdict.SOUND, CapabilityTag.GLOBAL);
        seedWithCapability(actorId, now.minus(2, ChronoUnit.DAYS), AttestationVerdict.SOUND, CapabilityTag.GLOBAL);
        seedWithCapability(actorId, now.minus(3, ChronoUnit.DAYS), AttestationVerdict.ENDORSED, CapabilityTag.GLOBAL);

        trustScoreJob.runComputation();

        final ActorTrustScore global = trustRepo.findByActorId(actorId).orElseThrow();
        assertThat(global.trustScore).isGreaterThan(0.7);

        assertThat(trustRepo.findByActorIdAndScoreType(actorId, ScoreType.CAPABILITY)).isEmpty();
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private void seedWithCapability(final String actorId, final Instant decisionTime,
            final AttestationVerdict verdictOrNull, final String capabilityTag) {
        LedgerTestFixtures.seedDecision(actorId, decisionTime, verdictOrNull,
                verdictOrNull != null ? decisionTime.plusSeconds(60) : null,
                capabilityTag, repo, em);
    }
}
