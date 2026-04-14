package io.quarkiverse.ledger.runtime.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.quarkiverse.ledger.runtime.config.LedgerConfig;
import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.model.LedgerAttestation;
import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkiverse.ledger.runtime.repository.ActorTrustScoreRepository;
import io.quarkiverse.ledger.runtime.repository.LedgerEntryRepository;
import io.quarkus.scheduler.Scheduled;

/**
 * Nightly scheduled job that recomputes EigenTrust-inspired trust scores for all
 * decision-making actors in the ledger.
 *
 * <p>
 * The job is gated by {@code quarkus.ledger.trust-score.enabled}. When disabled, the
 * scheduled trigger fires but immediately returns without doing any work.
 *
 * <p>
 * {@link #runComputation()} is exposed with package-accessible visibility for direct
 * invocation in integration tests where the scheduler is disabled via a test profile.
 */
@ApplicationScoped
public class TrustScoreJob {

    @Inject
    LedgerEntryRepository ledgerRepo;

    @Inject
    ActorTrustScoreRepository trustRepo;

    @Inject
    LedgerConfig config;

    /**
     * Scheduled entry point — runs every 24 hours.
     * Delegates to {@link #runComputation()} when trust scoring is enabled.
     */
    @Scheduled(every = "24h", identity = "ledger-trust-score-job")
    @Transactional
    public void computeTrustScores() {
        if (!config.trustScore().enabled()) {
            return;
        }
        runComputation();
    }

    /**
     * Perform the full trust score computation.
     *
     * <p>
     * Loads all EVENT entries, groups them by actor, loads all attestations for those entries,
     * then upserts an {@code ActorTrustScore} row for each actor.
     * Exposed for direct invocation in tests (scheduler disabled in test profile).
     */
    @Transactional
    public void runComputation() {
        final TrustScoreComputer computer = new TrustScoreComputer(config.trustScore().decayHalfLifeDays());
        final Instant now = Instant.now();

        final List<LedgerEntry> allEvents = ledgerRepo.findAllEvents();
        final Map<String, List<LedgerEntry>> byActor = allEvents.stream()
                .filter(e -> e.actorId != null)
                .collect(Collectors.groupingBy(e -> e.actorId));

        final Set<UUID> entryIds = allEvents.stream()
                .map(e -> e.id)
                .collect(Collectors.toSet());
        final Map<UUID, List<LedgerAttestation>> attestationsByEntry = ledgerRepo.findAttestationsForEntries(entryIds);

        for (final Map.Entry<String, List<LedgerEntry>> actorEntry : byActor.entrySet()) {
            final String actorId = actorEntry.getKey();
            final List<LedgerEntry> decisions = actorEntry.getValue();
            final ActorType actorType = decisions.stream()
                    .map(e -> e.actorType)
                    .filter(t -> t != null)
                    .findFirst()
                    .orElse(ActorType.HUMAN);

            final TrustScoreComputer.ActorScore score = computer.compute(decisions, attestationsByEntry, now);

            trustRepo.upsert(actorId, actorType, score.trustScore(), score.decisionCount(),
                    score.overturnedCount(), score.appealCount(),
                    score.attestationPositive(), score.attestationNegative(), now);
        }
    }
}
