package io.quarkiverse.ledger.runtime.privacy;

/**
 * SPI for sanitising {@code ComplianceSupplement.decisionContext} JSON before persist.
 *
 * <p>
 * The default implementation is pass-through. Replace with a custom CDI bean to strip
 * PII from decision context blobs before they reach the ledger.
 */
public interface DecisionContextSanitiser {

    /**
     * Sanitise a decision context JSON string before it is persisted.
     *
     * @param decisionContextJson the raw JSON; may be {@code null}
     * @return sanitised JSON, or {@code null} if input is {@code null}
     */
    String sanitise(String decisionContextJson);
}
