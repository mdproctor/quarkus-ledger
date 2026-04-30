package io.casehub.ledger.api.model.supplement;

/**
 * Supplement carrying compliance, governance, and GDPR Art.22 decision snapshot fields.
 *
 * <h2>GDPR Article 22 — Automated Decision-Making</h2>
 * <p>
 * Article 22 of the GDPR requires that automated decisions be explainable. Data subjects
 * have the right to receive "meaningful information about the logic involved" in any
 * automated decision that significantly affects them. The following fields provide the
 * structured evidence needed to satisfy this requirement:
 * <ul>
 * <li>{@link #algorithmRef} — identifies which model, rule engine, or algorithm version
 * produced the decision, enabling reproducibility and audit.</li>
 * <li>{@link #confidenceScore} — the producing system's stated confidence (0.0–1.0),
 * satisfying the requirement to disclose "the significance and envisaged
 * consequences" of the decision.</li>
 * <li>{@link #contestationUri} — where the data subject can request human review
 * or challenge the decision, satisfying the right to contest under Art.22(3).</li>
 * <li>{@link #humanOverrideAvailable} — whether a human review path exists,
 * satisfying the Art.22(2) safeguard requirement.</li>
 * <li>{@link #decisionContext} — full JSON snapshot of observable state at the moment
 * of the decision, providing the "meaningful information" required by Arts.13–15.</li>
 * </ul>
 *
 * <h2>Governance fields</h2>
 * <p>
 * {@link #planRef} and {@link #rationale} record the policy version and stated basis
 * for the decision. {@link #evidence} and {@link #detail} carry structured evidence
 * and free-text overflow respectively.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * ComplianceSupplement cs = new ComplianceSupplement();
 * cs.algorithmRef = "classification-model-v3.2";
 * cs.confidenceScore = 0.91;
 * cs.contestationUri = "https://example.com/decisions/challenge";
 * cs.humanOverrideAvailable = true;
 * cs.decisionContext = "{\"inputs\":{\"riskScore\":42}}";
 * entry.attach(cs);
 * }</pre>
 */
public class ComplianceSupplement extends LedgerSupplement {

    // ── Governance ────────────────────────────────────────────────────────────

    /** Reference to the policy or procedure version that governed this action. */
    public String planRef;

    /** The actor's stated basis for the decision. */
    public String rationale;

    /** Structured evidence supplied by the actor. */
    public String evidence;

    /** Free-text or JSON detail — delegation targets, rejection reasons, etc. */
    public String detail;

    // ── GDPR Art.22 / EU AI Act Art.12 decision snapshot ─────────────────────

    /**
     * Full JSON snapshot of observable state at the moment of this decision.
     * Provides the "meaningful information about the logic involved" required by
     * GDPR Arts.13–15 and the technical logging required by EU AI Act Art.12.
     */
    public String decisionContext;

    /**
     * Identifier of the model, rule engine, or algorithm version that produced
     * the decision. Examples: {@code "gpt-4o"}, {@code "risk-classifier-v2.1"},
     * {@code "approval-rules-2026-Q1"}. Required for reproducibility audits.
     */
    public String algorithmRef;

    /**
     * The producing system's stated confidence in this decision, in the range
     * 0.0 (no confidence) to 1.0 (certainty). Null when not applicable (e.g.
     * deterministic rule engines). Satisfies the GDPR requirement to disclose
     * the significance and envisaged consequences of the decision.
     */
    public Double confidenceScore;

    /**
     * URI where the data subject can request human review or formally challenge
     * this decision, satisfying the contestation right under GDPR Art.22(3).
     * Example: {@code "https://example.com/decisions/{entryId}/challenge"}.
     */
    public String contestationUri;

    /**
     * Whether a human review path exists for this decision, satisfying the
     * Art.22(2)(b) safeguard requirement.
     */
    public Boolean humanOverrideAvailable;
}
