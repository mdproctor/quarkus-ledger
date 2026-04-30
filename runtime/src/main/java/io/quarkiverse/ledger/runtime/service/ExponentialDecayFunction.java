package io.casehub.ledger.runtime.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.runtime.config.LedgerConfig;
import io.quarkus.arc.DefaultBean;

/**
 * Default decay function: exponential decay {@code 2^(-ageInDays / halfLifeDays)} with an
 * asymmetric valence multiplier. FLAGGED/CHALLENGED attestations use a multiplier < 1.0
 * (configurable via {@code casehub.ledger.decay.flagged-persistence-multiplier}), causing
 * them to decay slower — they persist longer as negative evidence in the trust model.
 */
@ApplicationScoped
@DefaultBean
public class ExponentialDecayFunction implements DecayFunction {

    private final int halfLifeDays;
    private final double flaggedPersistenceMultiplier;

    @Inject
    public ExponentialDecayFunction(final LedgerConfig config) {
        this.halfLifeDays = config.trustScore().decayHalfLifeDays();
        this.flaggedPersistenceMultiplier = config.decay().flaggedPersistenceMultiplier();
    }

    @Override
    public double weight(final long ageInDays, final AttestationVerdict verdict) {
        final double base = Math.pow(2.0, -(double) ageInDays / halfLifeDays);
        final double multiplier = isNegative(verdict) ? flaggedPersistenceMultiplier : 1.0;
        return base * multiplier;
    }

    private static boolean isNegative(final AttestationVerdict verdict) {
        return verdict == AttestationVerdict.FLAGGED || verdict == AttestationVerdict.CHALLENGED;
    }
}
