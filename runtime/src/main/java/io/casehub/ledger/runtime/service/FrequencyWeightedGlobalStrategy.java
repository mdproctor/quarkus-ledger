package io.casehub.ledger.runtime.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.casehub.ledger.api.model.CapabilityTag;
import io.casehub.ledger.runtime.model.LedgerAttestation;

/**
 * Alternative {@link GlobalScoreStrategy}: derives the global score as a frequency-weighted
 * combination of per-capability scores.
 *
 * <p>
 * Backed by Fan et al. (2015) — the overall trust score is computed as a weighted average of
 * dimension-specific scores, where each dimension's weight reflects its relative frequency:
 * {@code globalScore = Σ (count_i / totalCount) × trustScore_i}.
 *
 * <p>
 * {@link #selectAttestations} returns an empty list — the Beta model is bypassed.
 * {@link #derive} computes the weighted combination after capability scores are available.
 * Returns empty if no capability scores exist (no override; job uses Beta prior 0.5).
 *
 * <p>
 * <b>Note on alpha/beta fields:</b> The {@link TrustScoreComputer.ActorScore} returned by
 * {@link #derive} combines alpha/beta from multiple Beta models using a weighted average.
 * The resulting alpha/beta values are approximations — {@code alpha/(alpha+beta)} does not
 * equal {@code trustScore} exactly because the two quantities are computed on different
 * formula paths. Consumers should read only {@code trustScore} from GLOBAL rows computed
 * by this strategy; alpha/beta are not valid Beta posterior parameters in this context.
 * See ADR 0008 for the design rationale and known limitations.
 *
 * <p>
 * Activate via {@code quarkus.arc.selected-alternatives=
 * io.casehub.ledger.runtime.service.FrequencyWeightedGlobalStrategy}.
 */
@ApplicationScoped
@Alternative
public class FrequencyWeightedGlobalStrategy implements GlobalScoreStrategy {

    @Override
    public List<LedgerAttestation> selectAttestations(final List<LedgerAttestation> all) {
        return List.of();
    }

    @Override
    public Optional<TrustScoreComputer.ActorScore> derive(
            final Map<String, TrustScoreComputer.ActorScore> capabilityScores,
            final List<LedgerAttestation> allAttestations) {

        if (capabilityScores.isEmpty()) {
            return Optional.empty();
        }

        // Count capability-specific attestations per tag (exclude global "*")
        final Map<String, Long> countPerTag = allAttestations.stream()
                .filter(a -> !CapabilityTag.GLOBAL.equals(a.capabilityTag))
                .collect(Collectors.groupingBy(a -> a.capabilityTag, Collectors.counting()));

        final long totalCount = countPerTag.values().stream().mapToLong(Long::longValue).sum();
        if (totalCount == 0) {
            return Optional.empty();
        }

        double weightedScore = 0.0;
        // Alpha and beta are combined proportionally; priors (1.0) are added once, not per-capability
        double combinedAlpha = 1.0;
        double combinedBeta = 1.0;
        int totalDecisions = 0;
        int totalOverturned = 0;
        int totalPositive = 0;
        int totalNegative = 0;

        for (final Map.Entry<String, TrustScoreComputer.ActorScore> entry : capabilityScores.entrySet()) {
            final long count = countPerTag.getOrDefault(entry.getKey(), 0L);
            if (count == 0) {
                continue;
            }
            final double weight = (double) count / totalCount;
            final TrustScoreComputer.ActorScore cs = entry.getValue();
            weightedScore += weight * cs.trustScore();
            combinedAlpha += weight * (cs.alpha() - 1.0);
            combinedBeta += weight * (cs.beta() - 1.0);
            totalDecisions += cs.decisionCount();
            totalOverturned += cs.overturnedCount();
            totalPositive += cs.attestationPositive();
            totalNegative += cs.attestationNegative();
        }

        final double clampedScore = Math.max(0.0, Math.min(1.0, weightedScore));
        return Optional.of(new TrustScoreComputer.ActorScore(
                clampedScore, combinedAlpha, combinedBeta,
                totalDecisions, totalOverturned, totalPositive, totalNegative));
    }
}
