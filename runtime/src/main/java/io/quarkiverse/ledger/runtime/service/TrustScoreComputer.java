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
 * decisions, compute a decay weight via the injected {@link DecayFunction} using the
 * attestation's own {@code occurredAt}. SOUND/ENDORSED increments α by
 * {@code decayWeight × confidence}; FLAGGED/CHALLENGED increments β by
 * {@code decayWeight × confidence}. Score = α/(α+β), clamped to [0.0, 1.0].
 *
 * <p>
 * The default {@link ExponentialDecayFunction} applies exponential decay with an
 * asymmetric valence multiplier — FLAGGED attestations decay slower, persisting longer
 * as negative evidence.
 *
 * <p>
 * Properties: no history → 0.5 (maximum uncertainty). Unattested decisions contribute
 * nothing — they do not inflate the score.
 */
public final class TrustScoreComputer {

    private final DecayFunction decayFunction;

    /**
     * CDI/production constructor — delegates decay to the supplied {@link DecayFunction}.
     *
     * @param decayFunction the decay strategy to apply
     */
    public TrustScoreComputer(final DecayFunction decayFunction) {
        this.decayFunction = decayFunction;
    }

    /**
     * Convenience constructor for unit tests — uses simple exponential decay
     * ({@code 2^(-ageInDays / halfLifeDays)}) with no valence asymmetry.
     *
     * @param halfLifeDays recency decay half-life in days; values ≤ 0 default to 90
     */
    public TrustScoreComputer(final int halfLifeDays) {
        final int effective = halfLifeDays > 0 ? halfLifeDays : 90;
        this.decayFunction = (ageInDays, verdict) -> Math.pow(2.0, -(double) ageInDays / effective);
    }

    /**
     * The computed score and metrics for one actor.
     *
     * @param trustScore computed trust score in [0.0, 1.0]
     * @param alpha final α value (prior 1.0 + positive recency-weighted contributions)
     * @param beta final β value (prior 1.0 + negative recency-weighted contributions)
     * @param decisionCount number of EVENT entries evaluated
     * @param overturnedCount number of decisions with at least one positive-weight negative attestation
     * @param attestationPositive total positive attestation count (raw, regardless of weight)
     * @param attestationNegative total negative attestation count (raw, regardless of weight)
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
                final double decayWeight = decayFunction.weight(ageInDays, attestation.verdict);
                final double weight = decayWeight * Math.max(0.0, Math.min(1.0, attestation.confidence));

                if (attestation.verdict == AttestationVerdict.SOUND
                        || attestation.verdict == AttestationVerdict.ENDORSED) {
                    alpha += weight;
                    totalPositive++;
                } else if (attestation.verdict == AttestationVerdict.FLAGGED
                        || attestation.verdict == AttestationVerdict.CHALLENGED) {
                    beta += weight;
                    totalNegative++;
                    if (weight > 0.0) {
                        hasNegative = true;
                    }
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
