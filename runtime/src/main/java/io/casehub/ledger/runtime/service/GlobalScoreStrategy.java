package io.casehub.ledger.runtime.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.casehub.ledger.runtime.model.LedgerAttestation;

/**
 * SPI for determining which attestations contribute to the GLOBAL {@link io.casehub.ledger.runtime.model.ActorTrustScore}.
 *
 * <p>
 * Three built-in implementations reflect the three literature-backed positions on what
 * the global score should aggregate:
 * <ul>
 * <li>{@link AllAttestationsGlobalStrategy} (default) — all attestations; Wang &amp; Vassileva root-node model</li>
 * <li>{@link ExplicitGlobalAttestationsStrategy} — only {@code capabilityTag = "*"} attestations</li>
 * <li>{@link FrequencyWeightedGlobalStrategy} — derived as frequency-weighted combination of capability scores; Fan et al. (2015)</li>
 * </ul>
 *
 * <p>
 * Alternative implementations register as {@code @Alternative} CDI beans and are activated via
 * {@code quarkus.arc.selected-alternatives=<fully-qualified-class-name>} in {@code application.properties}.
 *
 * <p>
 * See ADR 0008 and issue #61 for the research background and decision rationale.
 */
public interface GlobalScoreStrategy {

    /**
     * Select which attestations feed the global Beta model.
     *
     * @param all all attestations for the actor's decisions (may be empty)
     * @return a subset of {@code all} using the same object references — implementations must
     *         not construct new {@link LedgerAttestation} instances. The caller uses reference
     *         equality ({@link java.util.HashSet}) to map the result back to entry buckets;
     *         returning new instances would silently produce an empty global Beta model.
     */
    List<LedgerAttestation> selectAttestations(List<LedgerAttestation> all);

    /**
     * Optionally derive the global score from already-computed capability scores,
     * overriding the Beta model result from {@link #selectAttestations}.
     *
     * <p>
     * Called after all CAPABILITY rows are upserted. Return empty to keep the
     * Beta model score unchanged (default behaviour for Options A and B).
     * Option C returns a frequency-weighted combination here.
     *
     * @param capabilityScores map of capabilityTag to computed ActorScore for that capability
     * @param allAttestations all attestations for the actor (same list passed to {@link #selectAttestations})
     * @return derived score to override the Beta model result, or empty to use the Beta score
     */
    default Optional<TrustScoreComputer.ActorScore> derive(
            final Map<String, TrustScoreComputer.ActorScore> capabilityScores,
            final List<LedgerAttestation> allAttestations) {
        return Optional.empty();
    }
}
