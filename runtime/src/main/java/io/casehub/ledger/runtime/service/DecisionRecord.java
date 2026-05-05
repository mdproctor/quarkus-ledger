package io.casehub.ledger.runtime.service;

import java.time.Instant;
import java.util.UUID;

/**
 * A single automated decision record in a {@link ComplianceReport}.
 *
 * <p>
 * Populated from a {@link io.casehub.ledger.runtime.model.LedgerEntry} that carries a
 * {@link io.casehub.ledger.runtime.model.supplement.ComplianceSupplement}. Provenance
 * fields ({@link #sourceEntityType}, {@link #sourceEntityId}) are populated when the entry
 * also has a {@link io.casehub.ledger.runtime.model.supplement.ProvenanceSupplement}.
 *
 * @param entryId                 UUID of the ledger entry (for Merkle inclusion proof look-up)
 * @param occurredAt              when the automated decision was recorded
 * @param algorithmRef            the model, rule engine, or algorithm version that produced the decision
 * @param confidenceScore         the producing system's stated confidence (0.0–1.0); null when not applicable
 * @param contestationUri         where the subject can request human review under GDPR Art.22(3)
 * @param humanOverrideAvailable  whether a human review path exists (GDPR Art.22(2) safeguard)
 * @param sourceEntityType        external entity type from ProvenanceSupplement; null when absent
 * @param sourceEntityId          external entity identifier from ProvenanceSupplement; null when absent
 */
public record DecisionRecord(
        UUID entryId,
        Instant occurredAt,
        String algorithmRef,
        Double confidenceScore,
        String contestationUri,
        Boolean humanOverrideAvailable,
        String sourceEntityType,
        String sourceEntityId) {
}
