package io.casehub.ledger.examples.routing;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.ledger.examples.routing.ledger.TaskLedgerEntry;
import io.casehub.ledger.examples.routing.routing.RoutingSignalLogger;
import io.casehub.ledger.examples.routing.routing.TaskRouter;
import io.casehub.ledger.runtime.model.ActorType;
import io.casehub.ledger.runtime.model.AttestationVerdict;
import io.casehub.ledger.runtime.model.LedgerAttestation;
import io.casehub.ledger.runtime.model.LedgerEntryType;
import io.casehub.ledger.runtime.service.TrustScoreJob;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class TrustScoreRoutingE2EIT {

    @Inject
    TrustScoreJob trustScoreJob;

    @Inject
    EntityManager em;

    @Inject
    TaskRouter taskRouter;

    @BeforeEach
    void resetLatch() {
        RoutingSignalLogger.resetLatch();
    }

    // ── Happy path: full pipeline — entries → scores → routing ────────────────

    @Test
    @Transactional
    void highTrustAgent_rankedAboveLowTrustAgent() {
        final String highTrust = "e2e-high-" + UUID.randomUUID();
        final String lowTrust = "e2e-low-" + UUID.randomUUID();

        // high-trust: 3 positive attestations
        seedTask(highTrust, AttestationVerdict.SOUND);
        seedTask(highTrust, AttestationVerdict.SOUND);
        seedTask(highTrust, AttestationVerdict.ENDORSED);

        // low-trust: 3 negative attestations
        seedTask(lowTrust, AttestationVerdict.FLAGGED);
        seedTask(lowTrust, AttestationVerdict.CHALLENGED);
        seedTask(lowTrust, AttestationVerdict.FLAGGED);

        trustScoreJob.runComputation();

        final List<String> ranked = taskRouter.getRankedAgents();
        assertThat(ranked).contains(highTrust, lowTrust);
        assertThat(ranked.indexOf(highTrust)).isLessThan(ranked.indexOf(lowTrust));
    }

    // ── Happy path: REST endpoint returns ranked agents ───────────────────────

    @Test
    @Transactional
    void rankedAgentsEndpoint_returnsNonEmptyList() {
        seedTask("e2e-rest-" + UUID.randomUUID(), AttestationVerdict.SOUND);

        trustScoreJob.runComputation();

        given()
                .when().get("/routing/ranked-agents")
                .then()
                .statusCode(200)
                .body("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.empty()));
    }

    // ── Happy path: async logger receives notification ─────────────────────────

    @Test
    @Transactional
    void asyncLogger_receivesNotificationAfterComputation() throws InterruptedException {
        seedTask("e2e-async-" + UUID.randomUUID(), AttestationVerdict.SOUND);

        trustScoreJob.runComputation();

        final boolean called = RoutingSignalLogger.notifyLatch.await(5, TimeUnit.SECONDS);
        assertThat(called).as("Async notification logger must be triggered within 5s").isTrue();
    }

    // ── Correctness: ranking is stable across two runs ─────────────────────────

    @Test
    @Transactional
    void twoRuns_rankingStableWhenScoresUnchanged() {
        final String agentA = "e2e-stable-a-" + UUID.randomUUID();
        final String agentB = "e2e-stable-b-" + UUID.randomUUID();
        seedTask(agentA, AttestationVerdict.SOUND);
        seedTask(agentA, AttestationVerdict.SOUND);
        seedTask(agentB, AttestationVerdict.SOUND);

        trustScoreJob.runComputation();
        final List<String> firstRanking = taskRouter.getRankedAgents().stream()
                .filter(a -> a.equals(agentA) || a.equals(agentB)).toList();

        trustScoreJob.runComputation();
        final List<String> secondRanking = taskRouter.getRankedAgents().stream()
                .filter(a -> a.equals(agentA) || a.equals(agentB)).toList();

        assertThat(firstRanking).isEqualTo(secondRanking);
    }

    // ── Fixture ───────────────────────────────────────────────────────────────

    private void seedTask(final String actorId, final AttestationVerdict verdict) {
        final Instant now = Instant.now().minus(1, ChronoUnit.DAYS);

        final TaskLedgerEntry entry = new TaskLedgerEntry();
        entry.subjectId = UUID.randomUUID();
        entry.sequenceNumber = 1;
        entry.entryType = LedgerEntryType.EVENT;
        entry.actorId = actorId;
        entry.actorType = ActorType.AGENT;
        entry.actorRole = "TaskAgent";
        entry.occurredAt = now;
        entry.taskType = "classification";
        em.persist(entry);
        em.flush(); // ensure id is assigned before referencing in attestation

        final LedgerAttestation att = new LedgerAttestation();
        att.ledgerEntryId = entry.id;
        att.subjectId = entry.subjectId;
        att.attestorId = "e2e-attestor";
        att.attestorType = ActorType.SYSTEM;
        att.verdict = verdict;
        att.confidence = 0.9;
        att.occurredAt = now.plusSeconds(60);
        em.persist(att);
    }
}
