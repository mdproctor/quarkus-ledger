package io.casehub.ledger.runtime.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import io.casehub.ledger.api.model.CapabilityTag;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.ledger.runtime.config.LedgerConfig;
import io.casehub.ledger.runtime.model.ActorTrustScore;
import io.casehub.ledger.runtime.model.LedgerAttestation;
import io.casehub.ledger.runtime.model.LedgerEntry;
import io.casehub.ledger.runtime.persistence.LedgerPersistenceUnit;
import io.casehub.ledger.runtime.repository.ActorTrustScoreRepository;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;
import io.casehub.ledger.runtime.service.routing.TrustScoreRoutingPublisher;
import io.quarkus.scheduler.Scheduled;

/**
 * Nightly scheduled job that recomputes Bayesian Beta trust scores for all
 * decision-making actors in the ledger.
 *
 * <p>
 * The job is gated by {@code casehub.ledger.trust-score.enabled}. When disabled, the
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

    @Inject
    TrustScoreRoutingPublisher routingPublisher;

    @Inject
    DecayFunction decayFunction;

    @Inject
    GlobalScoreStrategy globalScoreStrategy;

    @Inject
    @LedgerPersistenceUnit
    EntityManager em;

    @Scheduled(every = "{casehub.ledger.trust-score.schedule:24h}", identity = "ledger-trust-score-job")
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
        // Pre-read previous scores only if a delta observer is registered.
        // Entities are detached so subsequent upserts do not mutate the snapshot values.
        // GLOBAL rows only — CAPABILITY/DIMENSION rows have multiple rows per actor and would
        // cause Collectors.toMap to throw on duplicate actorId keys.
        final Map<String, ActorTrustScore> previousSnapshot;
        if (routingPublisher.needsPreviousSnapshot()) {
            previousSnapshot = trustRepo.findAll().stream()
                    .filter(s -> s.scoreType == ActorTrustScore.ScoreType.GLOBAL)
                    .peek(s -> em.detach(s))
                    .collect(Collectors.toMap(s -> s.actorId, s -> s));
        } else {
            previousSnapshot = Map.of();
        }

        final TrustScoreComputer computer = new TrustScoreComputer(decayFunction);
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

            // Collect all attestations for this actor's decisions
            final List<LedgerAttestation> actorAttestations = new ArrayList<>();
            for (final LedgerEntry decision : decisions) {
                actorAttestations.addAll(attestationsByEntry.getOrDefault(decision.id, List.of()));
            }

            // ── Capability pass ────────────────────────────────────────────────────────
            // Group actor's capability-specific attestations by (capabilityTag → entryId) in one pass
            final Map<String, Map<UUID, List<LedgerAttestation>>> byCapabilityAndEntry = actorAttestations.stream()
                    .filter(a -> !CapabilityTag.GLOBAL.equals(a.capabilityTag))
                    .collect(Collectors.groupingBy(
                            a -> a.capabilityTag,
                            Collectors.groupingBy(a -> a.ledgerEntryId)));

            final Map<String, TrustScoreComputer.ActorScore> capabilityScores = new LinkedHashMap<>();

            for (final Map.Entry<String, Map<UUID, List<LedgerAttestation>>> capEntry : byCapabilityAndEntry.entrySet()) {
                final String capabilityTag = capEntry.getKey();
                final Map<UUID, List<LedgerAttestation>> capByEntry = capEntry.getValue();

                final TrustScoreComputer.ActorScore capScore = computer.compute(decisions, capByEntry, now);
                trustRepo.upsert(actorId, ActorTrustScore.ScoreType.CAPABILITY, capabilityTag,
                        actorType, capScore.trustScore(),
                        capScore.decisionCount(), capScore.overturnedCount(),
                        capScore.alpha(), capScore.beta(),
                        capScore.attestationPositive(), capScore.attestationNegative(), now);
                capabilityScores.put(capabilityTag, capScore);
            }

            // ── Dimension pass ─────────────────────────────────────────────────────────
            // Group actor's dimension-tagged attestations by dimension in one pass.
            // Excludes attestations with null dimensionScore — they carry no quality signal.
            final Map<String, List<LedgerAttestation>> byDimension = actorAttestations.stream()
                    .filter(a -> a.trustDimension != null && a.dimensionScore != null)
                    .collect(Collectors.groupingBy(a -> a.trustDimension));

            for (final Map.Entry<String, List<LedgerAttestation>> dimEntry : byDimension.entrySet()) {
                final String dimension = dimEntry.getKey();
                final List<LedgerAttestation> dimAttestations = dimEntry.getValue();

                computer.computeDimensionScore(dimAttestations, now).ifPresent(dimScore -> {
                    final int dimPositive = (int) dimAttestations.stream()
                            .filter(a -> a.dimensionScore != null && a.dimensionScore > 0.5).count();
                    final int dimNegative = (int) dimAttestations.stream()
                            .filter(a -> a.dimensionScore != null && a.dimensionScore <= 0.5).count();
                    final int dimDecisionCount = (int) dimAttestations.stream()
                            .map(a -> a.ledgerEntryId).distinct().count();

                    trustRepo.upsert(actorId, ActorTrustScore.ScoreType.DIMENSION, dimension,
                            actorType, dimScore,
                            dimDecisionCount, 0,
                            0.0, 0.0,
                            dimPositive, dimNegative, now);
                });
            }

            // ── Global pass ────────────────────────────────────────────────────────────
            final List<LedgerAttestation> selectedAttestations =
                    globalScoreStrategy.selectAttestations(actorAttestations);
            final Set<LedgerAttestation> selectedSet = new HashSet<>(selectedAttestations);

            final Map<UUID, List<LedgerAttestation>> selectedByEntry = attestationsByEntry.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> e.getValue().stream()
                                    .filter(selectedSet::contains)
                                    .collect(Collectors.toList())));

            final TrustScoreComputer.ActorScore globalScore = computer.compute(decisions, selectedByEntry, now);
            final TrustScoreComputer.ActorScore finalScore =
                    globalScoreStrategy.derive(capabilityScores, actorAttestations)
                            .orElse(globalScore);

            trustRepo.upsert(actorId, ActorTrustScore.ScoreType.GLOBAL, null,
                    actorType, finalScore.trustScore(),
                    finalScore.decisionCount(), finalScore.overturnedCount(),
                    finalScore.alpha(), finalScore.beta(),
                    finalScore.attestationPositive(), finalScore.attestationNegative(), now);
        }

        if (config.trustScore().eigentrust().enabled()) {
            runEigenTrustPass(allEvents, attestationsByEntry);
        }

        // Routing signals — after all writes, within the same transaction
        // Detach before publish so observers receive value snapshots, not managed entities
        final List<ActorTrustScore> currentScores = trustRepo.findAll().stream()
                .peek(em::detach)
                .collect(Collectors.toList());
        routingPublisher.publish(currentScores, previousSnapshot, now);
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

        final Set<String> preTrustedActors = config.trustScore().eigentrust().preTrustedActors()
                .map(LinkedHashSet::new)
                .orElseGet(LinkedHashSet::new);

        final EigenTrustComputer eigenTrust = new EigenTrustComputer(
                config.trustScore().eigentrust().alpha());

        final Map<String, Double> globalScores = eigenTrust.compute(
                allAttestations, entryActorIndex, preTrustedActors);

        for (final Map.Entry<String, Double> entry : globalScores.entrySet()) {
            trustRepo.updateGlobalTrustScore(entry.getKey(), entry.getValue());
        }
    }
}
