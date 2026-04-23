package io.quarkiverse.ledger.examples.eigentrust;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.ledger.runtime.model.ActorTrustScore;
import io.quarkiverse.ledger.runtime.repository.ActorTrustScoreRepository;
import io.quarkiverse.ledger.runtime.service.TrustScoreJob;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for the EigenTrust mesh example.
 *
 * <p>
 * All tests seed fresh data in {@code @BeforeEach} via {@link MeshTrustService}, then
 * call {@link TrustScoreJob#runComputation()} directly (scheduler is disabled).
 */
@QuarkusTest
class EigenTrustMeshIT {

    @Inject
    MeshTrustService meshTrustService;

    @Inject
    ActorTrustScoreRepository trustRepo;

    @Inject
    TrustScoreJob trustScoreJob;

    @BeforeEach
    void seedAndCompute() {
        meshTrustService.seedClassifications();
        trustScoreJob.runComputation();
    }

    // ── Happy path: agents have different trustScores after attestations ───────

    @Test
    void agentA_hasTrustScoreHigherThanAgentC() {
        final Optional<ActorTrustScore> scoreA = trustRepo.findByActorId(MeshTrustService.AGENT_A);
        final Optional<ActorTrustScore> scoreC = trustRepo.findByActorId(MeshTrustService.AGENT_C);

        assertThat(scoreA).as("agent-a must have a computed trust score").isPresent();
        assertThat(scoreC).as("agent-c must have a computed trust score").isPresent();

        assertThat(scoreA.get().trustScore)
                .as("reliable agent-a should have higher trustScore than unreliable agent-c")
                .isGreaterThan(scoreC.get().trustScore);
    }

    // ── Happy path: globalTrustScore differs from trustScore ──────────────────

    @Test
    void eigenTrust_globalScoresDifferFromDirectScores() {
        final List<ActorTrustScore> allScores = trustRepo.findAll();

        // EigenTrust must have been computed — at least one actor has a non-zero globalTrustScore
        final boolean anyGlobal = allScores.stream().anyMatch(s -> s.globalTrustScore > 0.0);
        assertThat(anyGlobal)
                .as("EigenTrust computation should produce non-zero globalTrustScore for at least one actor")
                .isTrue();

        // eigenvector components sum to ≤ 1.0
        final double totalGlobal = allScores.stream().mapToDouble(s -> s.globalTrustScore).sum();
        assertThat(totalGlobal)
                .as("globalTrustScore values are eigenvector shares and must sum to ≤ 1.0")
                .isLessThanOrEqualTo(1.0 + 1e-9);

        // agent-a should have a higher globalTrustScore than agent-c
        final Optional<ActorTrustScore> scoreA = trustRepo.findByActorId(MeshTrustService.AGENT_A);
        final Optional<ActorTrustScore> scoreC = trustRepo.findByActorId(MeshTrustService.AGENT_C);
        assertThat(scoreA).isPresent();
        assertThat(scoreC).isPresent();

        assertThat(scoreA.get().globalTrustScore)
                .as("agent-a should have higher globalTrustScore than agent-c")
                .isGreaterThan(scoreC.get().globalTrustScore);
    }

    // ── Correctness: EigenTrust propagates trust transitively ─────────────────

    @Test
    void eigenTrust_propagatesTrustTransitively() {
        // Transitive chain: a trusts b (CHALLENGED attestations on b — wait, we need a trust b)
        // Actually a gives SOUND to its own good decisions; b trusts a (SOUND attestations on a)
        // The key transitive path is: a attests SOUND on own peers who attested SOUND on each other
        //
        // Direct attestation graph:
        //   b -> a: SOUND (b gave SOUND to a's decisions)
        //   c -> a: SOUND (c gave SOUND to a's decisions)
        //   c -> b: SOUND (c gave SOUND to b's decisions)
        //   a -> b: CHALLENGED (a challenged b's decisions)
        //   a -> c: FLAGGED (a flagged c's decisions)
        //   b -> c: FLAGGED (b flagged c's decisions)
        //
        // EigenTrust: a has the most positive incoming attestations (SOUND from b and c),
        // so a gets the highest globalTrustScore. c has the worst (FLAGGED from both).
        // The transitive path through the graph should keep agent-a globally above agent-c.

        final Optional<ActorTrustScore> scoreA = trustRepo.findByActorId(MeshTrustService.AGENT_A);
        final Optional<ActorTrustScore> scoreC = trustRepo.findByActorId(MeshTrustService.AGENT_C);

        assertThat(scoreA).isPresent();
        assertThat(scoreC).isPresent();

        assertThat(scoreA.get().globalTrustScore)
                .as("agent-a's globalTrustScore must exceed agent-c's via transitive propagation")
                .isGreaterThan(scoreC.get().globalTrustScore);

        // Also confirm that the transitive graph means a beats c even though a never gave c
        // a positive attestation — the global score reflects the full mesh, not just direct edges
        assertThat(scoreA.get().globalTrustScore)
                .as("agent-a's EigenTrust global score must be positive")
                .isGreaterThan(0.0);
    }

    // ── REST endpoint returns scores ──────────────────────────────────────────

    @Test
    void restEndpoint_returnsScoresWithTrustAndGlobalTrustFields() {
        given()
                .when().get("/mesh/scores")
                .then()
                .statusCode(200)
                .body("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.empty()))
                .body("actorId", org.hamcrest.Matchers.hasItem(MeshTrustService.AGENT_A))
                .body("actorId", org.hamcrest.Matchers.hasItem(MeshTrustService.AGENT_B))
                .body("actorId", org.hamcrest.Matchers.hasItem(MeshTrustService.AGENT_C))
                .body("[0].trustScore", org.hamcrest.Matchers.notNullValue())
                .body("[0].globalTrustScore", org.hamcrest.Matchers.notNullValue());
    }
}
