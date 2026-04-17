package io.quarkiverse.ledger.runtime.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import io.quarkiverse.ledger.runtime.model.AttestationVerdict;
import io.quarkiverse.ledger.runtime.model.LedgerAttestation;
import io.quarkiverse.ledger.runtime.model.LedgerEntry;

/**
 * Computes EigenTrust-inspired trust scores from ledger decision history.
 *
 * <p>
 * Pure Java — no CDI, no database. Accepts collections and returns a result record.
 * Suitable for use in unit tests without a Quarkus runtime.
 *
 * <p>
 * Algorithm:
 * <ul>
 * <li>Base score per decision: 1.0 if no negative attestations, 0.5 if mixed, 0.0 if majority negative</li>
 * <li>Recency weighting: {@code weight = 2^(-(ageInDays / halfLifeDays))}</li>
 * <li>{@code TrustScore = sum(weight * decisionScore) / sum(weight)}</li>
 * <li>Clamped to [0.0, 1.0]; neutral prior 0.5 when no history</li>
 * </ul>
 */
public final class TrustScoreComputer {

    private final int halfLifeDays;
    private final ForgivenessParams forgiveness;

    /**
     * Parameters for the optional forgiveness mechanism.
     *
     * <p>
     * The forgiveness factor {@code F = recencyForgiveness × frequencyLeniency}
     * is applied to negative decisions only:
     * {@code adjustedScore = decisionScore + F × (1.0 - decisionScore)}.
     *
     * <ul>
     * <li>{@code recencyForgiveness = 2^(-ageInDays / halfLifeDays)} — old failures fade</li>
     * <li>{@code frequencyLeniency = 1.0} if negative decisions ≤ threshold, else {@code 0.5}</li>
     * </ul>
     *
     * <p>
     * Use {@link #disabled()} for the default no-forgiveness path. The
     * {@link TrustScoreComputer#TrustScoreComputer(int)} single-param constructor delegates
     * to it — all existing behaviour is preserved when forgiveness is disabled.
     *
     * @param enabled whether forgiveness is active
     * @param frequencyThreshold negative decisions ≤ this receive full leniency; above → half
     * @param halfLifeDays forgiveness recency decay half-life in days
     */
    public record ForgivenessParams(boolean enabled, int frequencyThreshold, int halfLifeDays) {

        /** Returns a params instance that disables forgiveness entirely. */
        public static ForgivenessParams disabled() {
            return new ForgivenessParams(false, 0, 0);
        }
    }

    /**
     * Construct a computer with the given exponential-decay half-life and forgiveness disabled.
     *
     * @param halfLifeDays half-life in days; values {@code <= 0} default to 90
     */
    public TrustScoreComputer(final int halfLifeDays) {
        this(halfLifeDays, ForgivenessParams.disabled());
    }

    /**
     * Construct a computer with the given decay half-life and forgiveness configuration.
     *
     * @param halfLifeDays half-life in days; values {@code <= 0} default to 90
     * @param forgiveness forgiveness parameters; use {@link ForgivenessParams#disabled()} to
     *        reproduce the original behaviour exactly
     */
    public TrustScoreComputer(final int halfLifeDays, final ForgivenessParams forgiveness) {
        this.halfLifeDays = halfLifeDays > 0 ? halfLifeDays : 90;
        this.forgiveness = Objects.requireNonNull(forgiveness, "forgiveness must not be null");
    }

    /**
     * The computed score and metrics for one actor.
     *
     * @param trustScore computed trust score in [0.0, 1.0]
     * @param decisionCount number of EVENT entries evaluated
     * @param overturnedCount number of decisions with at least one negative attestation
     * @param appealCount appeal count (always 0; reserved for future use)
     * @param attestationPositive total positive attestation count
     * @param attestationNegative total negative attestation count
     */
    public record ActorScore(
            double trustScore,
            int decisionCount,
            int overturnedCount,
            int appealCount,
            int attestationPositive,
            int attestationNegative) {
    }

    /**
     * Compute a trust score for one actor.
     *
     * @param decisions ledger entries where this actor was the decision-maker (EVENT entries)
     * @param attestationsByEntryId map from ledger entry id to its attestations
     * @param now reference timestamp for age calculation
     * @return the computed score and metrics
     */
    public ActorScore compute(
            final List<LedgerEntry> decisions,
            final Map<UUID, List<LedgerAttestation>> attestationsByEntryId,
            final Instant now) {

        if (decisions.isEmpty()) {
            return new ActorScore(0.5, 0, 0, 0, 0, 0);
        }

        // Count negative decisions once — used by frequencyLeniency in forgiveness.
        // A decision is "negative" if it has at least one FLAGGED or CHALLENGED attestation.
        final long negativeDecisions = decisions.stream()
                .filter(e -> attestationsByEntryId.getOrDefault(e.id, List.of()).stream()
                        .anyMatch(a -> a.verdict == AttestationVerdict.FLAGGED
                                || a.verdict == AttestationVerdict.CHALLENGED))
                .count();

        double weightedPositive = 0.0;
        double weightedTotal = 0.0;
        int overturnedCount = 0;
        int totalPositive = 0;
        int totalNegative = 0;

        for (final LedgerEntry entry : decisions) {
            final Instant entryTime = entry.occurredAt != null ? entry.occurredAt : now;
            final long ageInDays = java.time.Duration.between(entryTime, now).toDays();
            final double weight = Math.pow(2.0, -(double) ageInDays / halfLifeDays);

            final List<LedgerAttestation> attestations = attestationsByEntryId.getOrDefault(entry.id, List.of());

            final long positive = attestations.stream()
                    .filter(a -> a.verdict == AttestationVerdict.SOUND
                            || a.verdict == AttestationVerdict.ENDORSED)
                    .count();
            final long negative = attestations.stream()
                    .filter(a -> a.verdict == AttestationVerdict.FLAGGED
                            || a.verdict == AttestationVerdict.CHALLENGED)
                    .count();

            totalPositive += (int) positive;
            totalNegative += (int) negative;
            if (negative > 0) {
                overturnedCount++;
            }

            // Decision score: 1.0 clean, 0.5 mixed, 0.0 predominantly negative
            final double decisionScore;
            if (negative == 0) {
                decisionScore = 1.0;
            } else if (positive > negative) {
                decisionScore = 0.5;
            } else {
                decisionScore = 0.0;
            }

            // Forgiveness: raise the effective score for negative decisions based on
            // recency (old failures fade) and frequency (one-offs forgiven more than patterns).
            // Clean decisions (score = 1.0) are not affected — the branch is not entered.
            final double effectiveScore;
            if (forgiveness.enabled() && decisionScore < 1.0) {
                final double recencyF = Math.pow(2.0, -(double) ageInDays / forgiveness.halfLifeDays());
                final double freqF = negativeDecisions <= forgiveness.frequencyThreshold() ? 1.0 : 0.5;
                effectiveScore = decisionScore + (recencyF * freqF) * (1.0 - decisionScore);
            } else {
                effectiveScore = decisionScore;
            }

            weightedPositive += weight * effectiveScore;
            weightedTotal += weight;
        }

        final double rawScore = weightedTotal > 0.0 ? weightedPositive / weightedTotal : 0.5;
        final double trustScore = Math.max(0.0, Math.min(1.0, rawScore));

        return new ActorScore(trustScore, decisions.size(), overturnedCount, 0, totalPositive, totalNegative);
    }
}
