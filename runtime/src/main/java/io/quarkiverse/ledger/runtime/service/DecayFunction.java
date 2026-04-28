package io.quarkiverse.ledger.runtime.service;

import io.quarkiverse.ledger.runtime.model.AttestationVerdict;

/**
 * SPI for computing an attestation's recency decay weight.
 *
 * <p>
 * The default implementation ({@link ExponentialDecayFunction}) applies exponential decay
 * with an asymmetric valence multiplier — FLAGGED/CHALLENGED attestations decay slower,
 * persisting longer in the trust model.
 *
 * <p>
 * Alternative strategies (linear, step, no-decay for testing) can be provided as
 * {@code @Alternative} CDI beans.
 */
@FunctionalInterface
public interface DecayFunction {

    /**
     * Compute the decay weight for an attestation of the given age and verdict.
     *
     * @param ageInDays age of the attestation in whole days (0 = today)
     * @param verdict the attestation verdict
     * @return weight in [0.0, 1.0]; 1.0 = full weight (age=0), 0.0 = fully decayed
     */
    double weight(long ageInDays, AttestationVerdict verdict);
}
