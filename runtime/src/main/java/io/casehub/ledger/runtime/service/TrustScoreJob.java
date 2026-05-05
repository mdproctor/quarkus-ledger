package io.casehub.ledger.runtime.service;

import java.time.Instant;
import java.util.ArrayList;
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
    AttestationAggregator attestationAggregator;

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
        final AttestationAggregator.Strategy aggregationStrategy = config.trustScore().aggregationStrategy();

        for (final Map.Entry<String, List<LedgerEntry>> actorEntry : byActor.entrySet()) {
            final String actorId = actorEntry.getKey();
            final List<LedgerEntry> decisions = actorEntry.getValue();
            final ActorType actorType = decisions.stream()
                    .map(e -> e.actorType)
                    .filter(t -> t != null)
                    .findFirst()
                    .orElse(ActorType.HUMAN);

            // Collect all attestations for this actor's decisions (used by the dimension pass and derive())
            final List<LedgerAttestation> actorAttestations = new ArrayList<>();
            for (final LedgerEntry decision : decisions) {
                actorAttestations.addAll(attestationsByEntry.getOrDefault(decision.id, List.of()));
            }

            // Build aggregated view for capability and global passes.
            // Dimension pass uses the original actorAttestations (dimensionScore is continuous, not verdict-based).
            final List<LedgerAttestation> effectiveAttestations =
                    buildEffectiveAttestations(decisions, attestationsByEntry, aggregationStrategy);

            // ── Capability pass ────────────────────────────────────────────────────────
            // Group aggregated attestations by (capabilityTag → entryId) in one pass
            final Map<String, Map<UUID, List<LedgerAttestation>>> byCapabilityAndEntry = effectiveAttestations.stream()
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
                    // scores >= 0.5 count as positive; < 0.5 count as negative; 0.5 (neutral) maps to positive
                    final int dimPositive = (int) dimAttestations.stream()
                            .filter(a -> a.dimensionScore >= 0.5).count();
                    final int dimNegative = (int) dimAttestations.stream()
                            .filter(a -> a.dimensionScore < 0.5).count();
                    // distinct entries where this actor was decision-maker, assessed on this dimension
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
            // selectAttestations filters by capabilityTag/etc. — synthetics preserve all fields.
            // Group directly by ledgerEntryId rather than using reference-equality set,
            // since effectiveAttestations are synthetic instances not in attestationsByEntry.
            final List<LedgerAttestation> selectedEffective =
                    globalScoreStrategy.selectAttestations(effectiveAttestations);
            final Map<UUID, List<LedgerAttestation>> selectedByEntry = selectedEffective.stream()
                    .collect(Collectors.groupingBy(a -> a.ledgerEntryId));

            final TrustScoreComputer.ActorScore globalScore = computer.compute(decisions, selectedByEntry, now);
            // derive() receives original actorAttestations — capability frequency counts stay accurate
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

    /**
     * Aggregates attestations per (entryId, capabilityTag) group and returns the flattened result.
     * Each group is reduced to a single synthetic {@link LedgerAttestation} carrying the consensus
     * verdict and aggregated confidence. The dimension pass is excluded — it uses raw attestations.
     */
    private List<LedgerAttestation> buildEffectiveAttestations(
            final List<LedgerEntry> decisions,
            final Map<UUID, List<LedgerAttestation>> attestationsByEntry,
            final AttestationAggregator.Strategy strategy) {
        final List<LedgerAttestation> result = new ArrayList<>();
        for (final LedgerEntry decision : decisions) {
            final List<LedgerAttestation> entryAttestations = attestationsByEntry.getOrDefault(decision.id, List.of());
            if (entryAttestations.isEmpty()) {
                continue;
            }
            // Aggregate per capabilityTag — different capability scopes are independent signals
            final Map<String, List<LedgerAttestation>> byCapTag = entryAttestations.stream()
                    .collect(Collectors.groupingBy(a -> a.capabilityTag != null ? a.capabilityTag : CapabilityTag.GLOBAL));
            for (final List<LedgerAttestation> group : byCapTag.values()) {
                attestationAggregator.aggregate(group, strategy)
                        .map(agg -> toSynthetic(agg, group.get(0)))
                        .ifPresent(result::add);
            }
        }
        return result;
    }

    /**
     * Builds a non-persisted synthetic {@link LedgerAttestation} from an aggregated result.
     * {@code id}, {@code attestorId}, and {@code attestorType} are intentionally left null —
     * the synthetic is never written to the database and is not attributed to a single attestor.
     * {@code trustDimension} and {@code dimensionScore} are copied for structural completeness
     * but are never read from synthetics; the dimension pass always uses raw attestations.
     */
    private static LedgerAttestation toSynthetic(
            final AttestationAggregator.AggregatedAttestation agg,
            final LedgerAttestation template) {
        final LedgerAttestation synthetic = new LedgerAttestation();
        synthetic.ledgerEntryId = template.ledgerEntryId;
        synthetic.subjectId = template.subjectId;
        synthetic.capabilityTag = template.capabilityTag;
        synthetic.trustDimension = template.trustDimension;
        synthetic.dimensionScore = template.dimensionScore;
        synthetic.verdict = agg.consensusVerdict();
        synthetic.confidence = agg.aggregatedConfidence();
        synthetic.occurredAt = template.occurredAt;
        synthetic.attestorRole = template.attestorRole;
        return synthetic;
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
