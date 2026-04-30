package io.casehub.ledger.service.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.runtime.model.ActorTrustScore;
import io.casehub.ledger.runtime.repository.ActorTrustScoreRepository;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;
import io.casehub.ledger.runtime.service.TrustScoreJob;
import io.casehub.ledger.service.LedgerTestFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(TrustScoreRoutingDisabledIT.RoutingDisabledTestProfile.class)
class TrustScoreRoutingDisabledIT {

    public static class RoutingDisabledTestProfile implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "routing-disabled-test";
        }
    }

    @Inject
    TrustScoreJob trustScoreJob;

    @Inject
    LedgerEntryRepository repo;

    @Inject
    ActorTrustScoreRepository trustRepo;

    @Inject
    EntityManager em;

    @Inject
    TestRoutingObservers observers;

    @BeforeEach
    void reset() {
        observers.reset();
    }

    // ── Robustness: routing-enabled=false → observers never called ─────────────

    @Test
    @Transactional
    void routingDisabled_observersNeverCalled() {
        seedDecision("routing-off-" + UUID.randomUUID(),
                Instant.now().minus(1, ChronoUnit.DAYS), AttestationVerdict.SOUND);

        trustScoreJob.runComputation();

        assertThat(observers.fullReceived()).isEmpty();
        assertThat(observers.deltaReceived()).isEmpty();
        assertThat(observers.notifyReceived()).isEmpty();
    }

    @Test
    @Transactional
    void routingDisabled_trustScoresStillComputed() {
        final String actorId = "routing-off-score-" + UUID.randomUUID();
        seedDecision(actorId, Instant.now().minus(1, ChronoUnit.DAYS), AttestationVerdict.SOUND);

        trustScoreJob.runComputation();

        final ActorTrustScore score = trustRepo.findByActorId(actorId).orElse(null);
        assertThat(score).isNotNull();
        assertThat(score.trustScore).isGreaterThan(0.5);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private void seedDecision(final String actorId, final Instant decisionTime,
            final AttestationVerdict verdict) {
        LedgerTestFixtures.seedDecision(actorId, decisionTime, verdict, repo, em);
    }
}
