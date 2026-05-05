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
     * <p>
     * The input list contains aggregated synthetic {@link LedgerAttestation} instances produced by
     * {@link AttestationAggregator} — one per {@code (entryId, capabilityTag)} group. The caller
     * groups the returned list by {@code ledgerEntryId} to build the per-entry attestation map
     * for {@link TrustScoreComputer#compute}. Implementations may filter by any field
     * ({@code capabilityTag}, {@code verdict}, etc.); all fields on the input instances are set.
     *
     * @param all all aggregated attestations for the actor's decisions (may be empty)
     * @return a subset of {@code all} to use for the global Beta computation; return the
     *         full list to include all attestations, or an empty list to skip global scoring
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
