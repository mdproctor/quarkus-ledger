package io.quarkiverse.ledger.service.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.ledger.runtime.model.AttestationVerdict;
import io.quarkiverse.ledger.runtime.repository.LedgerEntryRepository;
import io.quarkiverse.ledger.runtime.service.TrustScoreJob;
import io.quarkiverse.ledger.service.LedgerTestFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(TrustScoreRoutingIT.RoutingTestProfile.class)
class TrustScoreRoutingIT {

    public static class RoutingTestProfile implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "routing-test";
        }
    }

    @Inject
    TrustScoreJob trustScoreJob;

    @Inject
    LedgerEntryRepository repo;

    @Inject
    EntityManager em;

    @Inject
    TestRoutingObservers observers;

    @BeforeEach
    void reset() {
        observers.reset();
    }

    // ── Happy path: full observer receives all scores ─────────────────────────

    @Test
    @Transactional
    void fullObserver_receivesAllScoresAfterComputation() {
        final String actorId = "routing-full-" + UUID.randomUUID();
        seedDecision(actorId, Instant.now().minus(1, ChronoUnit.DAYS), AttestationVerdict.SOUND);

        trustScoreJob.runComputation();

        assertThat(observers.fullReceived()).hasSize(1);
        assertThat(observers.fullReceived().get(0).scores())
                .anyMatch(s -> s.actorId.equals(actorId));
    }

    // ── Happy path: notify observer receives correct actorCount ───────────────

    @Test
    @Transactional
    void notifyObserver_receivesCorrectActorCount() {
        final String actorA = "routing-notify-a-" + UUID.randomUUID();
        final String actorB = "routing-notify-b-" + UUID.randomUUID();
        seedDecision(actorA, Instant.now().minus(1, ChronoUnit.DAYS), AttestationVerdict.SOUND);
        seedDecision(actorB, Instant.now().minus(1, ChronoUnit.DAYS), AttestationVerdict.FLAGGED);

        trustScoreJob.runComputation();

        assertThat(observers.notifyReceived()).hasSize(1);
        assertThat(observers.notifyReceived().get(0).actorCount()).isGreaterThanOrEqualTo(2);
    }

    // ── Happy path: delta observer receives only changed actors on second run ──

    @Test
    @Transactional
    void deltaObserver_secondRun_receivesOnlyChangedActors() {
        final String stableActor = "routing-stable-" + UUID.randomUUID();
        final String changedActor = "routing-changed-" + UUID.randomUUID();

        // First run — both actors appear (first-run, previous=0.0)
        seedDecision(stableActor, Instant.now().minus(2, ChronoUnit.DAYS), AttestationVerdict.SOUND);
        seedDecision(changedActor, Instant.now().minus(2, ChronoUnit.DAYS), AttestationVerdict.SOUND);
        trustScoreJob.runComputation();
        observers.reset();

        // Second run — add a new FLAGGED attestation for changedActor only
        seedDecision(changedActor, Instant.now().minus(1, ChronoUnit.DAYS), AttestationVerdict.FLAGGED);
        trustScoreJob.runComputation();

        assertThat(observers.deltaReceived()).hasSize(1);
        final var deltas = observers.deltaReceived().get(0).deltas();
        assertThat(deltas).anyMatch(d -> d.actorId().equals(changedActor));
        assertThat(deltas).noneMatch(d -> d.actorId().equals(stableActor));
    }

    // ── Happy path: async observer receives notification ──────────────────────

    @Test
    @Transactional
    void asyncObserver_receivesNotification() throws InterruptedException {
        seedDecision("routing-async-" + UUID.randomUUID(),
                Instant.now().minus(1, ChronoUnit.DAYS), AttestationVerdict.SOUND);

        trustScoreJob.runComputation();

        final boolean called = TestRoutingObservers.asyncLatch.await(5, TimeUnit.SECONDS);
        assertThat(called).as("Async observer must be called within 5s").isTrue();
    }

    // ── Correctness: full payload is an immutable copy ─────────────────────────

    @Test
    @Transactional
    void fullPayload_scoresListIsImmutable() {
        seedDecision("routing-immut-" + UUID.randomUUID(),
                Instant.now().minus(1, ChronoUnit.DAYS), AttestationVerdict.SOUND);

        trustScoreJob.runComputation();

        assertThat(observers.fullReceived()).hasSize(1);
        final var scores = observers.fullReceived().get(0).scores();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> scores.add(new io.quarkiverse.ledger.runtime.model.ActorTrustScore()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── Robustness: no entries → empty scores → observers still called ─────────

    @Test
    void noEntries_observersCalledWithEmptyScores() {
        trustScoreJob.runComputation();

        assertThat(observers.notifyReceived()).hasSize(1);
        assertThat(observers.fullReceived()).hasSize(1);
        assertThat(observers.fullReceived().get(0).scores()).isEmpty();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private void seedDecision(final String actorId, final Instant decisionTime,
            final AttestationVerdict verdict) {
        LedgerTestFixtures.seedDecision(actorId, decisionTime, verdict, repo, em);
    }
}
