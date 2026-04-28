package io.quarkiverse.ledger.runtime.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.quarkiverse.ledger.runtime.model.AttestationVerdict;
import io.quarkiverse.ledger.runtime.model.LedgerAttestation;
import io.quarkiverse.ledger.runtime.model.LedgerEntry;

/**
 * Computes Bayesian Beta trust scores from ledger attestation history.
 *
 * <p>
 * Pure Java — no CDI, no database. Suitable for unit tests without a Quarkus runtime.
 *
 * <p>
 * Algorithm: start with prior Beta(1, 1). For each attestation across all of an actor's
 * decisions, compute {@code recencyWeight = 2^(-ageInDays / halfLifeDays)} using the
 * attestation's own {@code occurredAt}.
 * SOUND/ENDORSED increments α by {@code recencyWeight × confidence};
 * FLAGGED/CHALLENGED increments β by {@code recencyWeight × confidence}.
 * Confidence is clamped to [0.0, 1.0]. confidence=0.0 contributes nothing to the
 * score; confidence=1.0 is equivalent to the previous unweighted behaviour.
 * Score = α/(α+β), clamped to [0.0, 1.0].
 *
 * <p>
 * Properties: no history → 0.5 (maximum uncertainty). Unattested decisions contribute
 * nothing — they do not inflate the score. More evidence yields higher confidence:
 * 1 positive → ≈0.667; 100 positives → ≈0.990. Old negative attestations fade naturally
 * via recency weighting on β.
 */
public final class TrustScoreComputer {

    private final int halfLifeDays;

    /**
     * @param halfLifeDays recency decay half-life in days; values ≤ 0 default to 90
     */
    public TrustScoreComputer(final int halfLifeDays) {
        this.halfLifeDays = halfLifeDays > 0 ? halfLifeDays : 90;
    }

    /**
     * The computed score and metrics for one actor.
     *
     * @param trustScore computed trust score in [0.0, 1.0]
     * @param alpha final α value (prior 1.0 + positive recency-weighted contributions)
     * @param beta final β value (prior 1.0 + negative recency-weighted contributions)
     * @param decisionCount number of EVENT entries evaluated
     * @param overturnedCount number of decisions with at least one negative attestation
     * @param attestationPositive total positive attestation count
     * @param attestationNegative total negative attestation count
     */
    public record ActorScore(
            double trustScore,
            double alpha,
            double beta,
            int decisionCount,
            int overturnedCount,
            int attestationPositive,
            int attestationNegative) {
    }

    /**
     * Compute a Bayesian Beta trust score for one actor.
     *
     * @param decisions EVENT ledger entries where this actor was the decision-maker
     * @param attestationsByEntryId map from entry id to its attestations
     * @param now reference timestamp for age calculation
     * @return the computed score and metrics
     */
    public ActorScore compute(
            final List<LedgerEntry> decisions,
            final Map<UUID, List<LedgerAttestation>> attestationsByEntryId,
            final Instant now) {

        if (decisions.isEmpty()) {
            return new ActorScore(0.5, 1.0, 1.0, 0, 0, 0, 0);
        }

        double alpha = 1.0;
        double beta = 1.0;
        int overturnedCount = 0;
        int totalPositive = 0;
        int totalNegative = 0;

        for (final LedgerEntry entry : decisions) {
            final List<LedgerAttestation> attestations = attestationsByEntryId.getOrDefault(entry.id, List.of());
            boolean hasNegative = false;

            for (final LedgerAttestation attestation : attestations) {
                final Instant attestationTime = attestation.occurredAt != null ? attestation.occurredAt : now;
                final long ageInDays = java.time.Duration.between(attestationTime, now).toDays();
                final double recencyWeight = Math.pow(2.0, -(double) ageInDays / halfLifeDays);

                final double weight = recencyWeight * Math.max(0.0, Math.min(1.0, attestation.confidence));

                if (attestation.verdict == AttestationVerdict.SOUND
                        || attestation.verdict == AttestationVerdict.ENDORSED) {
                    alpha += weight;
                    totalPositive++;
                } else if (attestation.verdict == AttestationVerdict.FLAGGED
                        || attestation.verdict == AttestationVerdict.CHALLENGED) {
                    beta += weight;
                    totalNegative++;
                    hasNegative = true;
                }
            }
            if (hasNegative) {
                overturnedCount++;
            }
        }

        final double rawScore = alpha / (alpha + beta);
        final double trustScore = Math.max(0.0, Math.min(1.0, rawScore));

        return new ActorScore(
                trustScore, alpha, beta,
                decisions.size(), overturnedCount,
                totalPositive, totalNegative);
    }
}
