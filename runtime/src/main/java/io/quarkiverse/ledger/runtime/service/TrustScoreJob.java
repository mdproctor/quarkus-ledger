package io.quarkiverse.ledger.runtime.service;

import java.time.Instant;
import java.util.LinkedHashSet;
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
 * Nightly scheduled job that recomputes Bayesian Beta trust scores for all
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

    @Scheduled(every = "{quarkus.ledger.trust-score.schedule:24h}", identity = "ledger-trust-score-job")
    @Transactional
    public void computeTrustScores() {
        if (!config.trustScore().enabled()) {
            return;
        }
        runComputation();
    }

    /**
     * Loads all EVENT entries into memory before grouping by actor. For very large ledgers
     * this will become a bottleneck — a streaming or per-actor approach should be considered
     * when entry counts reach production scale.
     */
    @Transactional
    public void runComputation() {
        final TrustScoreComputer computer = new TrustScoreComputer(
                config.trustScore().decayHalfLifeDays());
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

            trustRepo.upsert(actorId, actorType, score.trustScore(),
                    score.decisionCount(), score.overturnedCount(),
                    score.alpha(), score.beta(),
                    score.attestationPositive(), score.attestationNegative(), now);
        }

        if (config.trustScore().eigentrustEnabled()) {
            runEigenTrustPass(allEvents, attestationsByEntry);
        }
    }

    private void runEigenTrustPass(
            final List<LedgerEntry> allEvents,
            final Map<UUID, List<LedgerAttestation>> attestationsByEntry) {

        final Map<UUID, String> entryActorIndex = allEvents.stream()
                .filter(e -> e.actorId != null)
                .collect(Collectors.toMap(e -> e.id, e -> e.actorId));

        final List<LedgerAttestation> allAttestations = attestationsByEntry.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());

        final Set<String> preTrustedActors = config.trustScore().preTrustedActors()
                .map(LinkedHashSet::new)
                .orElseGet(LinkedHashSet::new);

        final EigenTrustComputer eigenTrust = new EigenTrustComputer(
                config.trustScore().eigentrustAlpha());

        final Map<String, Double> globalScores = eigenTrust.compute(
                allAttestations, entryActorIndex, preTrustedActors);

        for (final Map.Entry<String, Double> entry : globalScores.entrySet()) {
            trustRepo.updateGlobalTrustScore(entry.getKey(), entry.getValue());
        }
    }
}
