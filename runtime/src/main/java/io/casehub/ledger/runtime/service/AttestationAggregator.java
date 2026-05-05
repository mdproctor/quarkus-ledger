package io.casehub.ledger.runtime.service;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.runtime.model.LedgerAttestation;

/**
 * Aggregates multiple attestations on the same ledger entry into a single
 * consensus verdict before trust scoring.
 *
 * <p>
 * When multiple agents attest to the same entry, treating each attestation
 * independently allows a low-confidence minority attestation to drag scores
 * disproportionately. Aggregation reduces this noise. The default strategy
 * ({@link Strategy#WEIGHTED_MAJORITY}) weights votes by confidence score.
 *
 * <p>
 * Pure CDI bean — no database dependency. Injected by {@link TrustScoreJob}.
 */
@ApplicationScoped
public class AttestationAggregator {

    /**
     * Determines how multiple attestations on the same entry are resolved.
     */
    public enum Strategy {
        /**
         * Confidence-weighted majority vote. Positive side = SOUND/ENDORSED,
         * negative side = FLAGGED/CHALLENGED. Winner is whichever side has the
         * higher total weighted confidence. Aggregated confidence = normalised margin.
         */
        WEIGHTED_MAJORITY,

        /**
         * All attestors must agree positively. Any FLAGGED or CHALLENGED attestation
         * produces a FLAGGED consensus regardless of count. Confidence = minimum
         * positive confidence when unanimous.
         */
        UNANIMOUS_REQUIRED,

        /**
         * Uses only the first attestation in the list, ignoring all others.
         * Equivalent to the pre-aggregation behaviour (single-attestation pass-through).
         */
        FIRST_ATTESTOR
    }

    /**
     * The result of aggregating multiple attestations into one consensus record.
     *
     * @param consensusVerdict SOUND represents a positive outcome; FLAGGED represents negative
     * @param aggregatedConfidence normalised confidence in [0.0, 1.0]
     * @param attestorCount number of input attestations considered
     */
    public record AggregatedAttestation(
            AttestationVerdict consensusVerdict,
            double aggregatedConfidence,
            int attestorCount) {
    }

    /**
     * Aggregate a list of attestations for a single ledger entry.
     *
     * @param attestations attestations on one ledger entry; order matters only for FIRST_ATTESTOR
     * @param strategy the aggregation strategy to apply
     * @return the aggregated result, or empty if the input list is empty
     */
    public Optional<AggregatedAttestation> aggregate(
            final List<LedgerAttestation> attestations,
            final Strategy strategy) {
        if (attestations.isEmpty()) {
            return Optional.empty();
        }
        return switch (strategy) {
            case WEIGHTED_MAJORITY -> weightedMajority(attestations);
            case UNANIMOUS_REQUIRED -> unanimousRequired(attestations);
            case FIRST_ATTESTOR -> firstAttestor(attestations);
        };
    }

    private Optional<AggregatedAttestation> weightedMajority(final List<LedgerAttestation> attestations) {
        double positive = 0.0;
        double negative = 0.0;

        for (final LedgerAttestation a : attestations) {
            final double weight = clamp(a.confidence);
            if (isPositive(a.verdict)) {
                positive += weight;
            } else if (isNegative(a.verdict)) {
                negative += weight;
            }
        }

        final double total = positive + negative;
        final AttestationVerdict verdict = positive >= negative
                ? AttestationVerdict.SOUND
                : AttestationVerdict.FLAGGED;
        // Normalised margin: how decisively the winning side won
        final double confidence = total > 0.0 ? Math.abs(positive - negative) / total : 1.0;
        return Optional.of(new AggregatedAttestation(verdict, confidence, attestations.size()));
    }

    private Optional<AggregatedAttestation> unanimousRequired(final List<LedgerAttestation> attestations) {
        double minPositiveConfidence = Double.MAX_VALUE;
        double maxNegativeConfidence = 0.0;
        boolean hasNegative = false;

        for (final LedgerAttestation a : attestations) {
            if (isNegative(a.verdict)) {
                hasNegative = true;
                maxNegativeConfidence = Math.max(maxNegativeConfidence, clamp(a.confidence));
            } else if (isPositive(a.verdict)) {
                minPositiveConfidence = Math.min(minPositiveConfidence, clamp(a.confidence));
            }
        }

        if (hasNegative) {
            // Use the highest-confidence dissenter — the most assertive veto drives the result.
            // Using max (not the first encountered) makes the outcome order-independent.
            return Optional.of(new AggregatedAttestation(AttestationVerdict.FLAGGED,
                    maxNegativeConfidence, attestations.size()));
        }
        final double confidence = minPositiveConfidence == Double.MAX_VALUE ? 0.0 : minPositiveConfidence;
        return Optional.of(new AggregatedAttestation(AttestationVerdict.SOUND, confidence, attestations.size()));
    }

    private Optional<AggregatedAttestation> firstAttestor(final List<LedgerAttestation> attestations) {
        final LedgerAttestation first = attestations.get(0);
        final AttestationVerdict verdict = isPositive(first.verdict)
                ? AttestationVerdict.SOUND
                : AttestationVerdict.FLAGGED;
        return Optional.of(new AggregatedAttestation(verdict, clamp(first.confidence), 1));
    }

    private static boolean isPositive(final AttestationVerdict verdict) {
        return verdict == AttestationVerdict.SOUND || verdict == AttestationVerdict.ENDORSED;
    }

    private static boolean isNegative(final AttestationVerdict verdict) {
        return verdict == AttestationVerdict.FLAGGED || verdict == AttestationVerdict.CHALLENGED;
    }

    private static double clamp(final double confidence) {
        return Math.max(0.0, Math.min(1.0, confidence));
    }
}
