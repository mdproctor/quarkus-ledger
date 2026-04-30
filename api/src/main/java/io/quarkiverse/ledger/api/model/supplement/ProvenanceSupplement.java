package io.casehub.ledger.api.model.supplement;

/**
 * Supplement carrying workflow provenance — the external entity that originated
 * this ledger entry's subject — and optional LLM agent configuration binding.
 *
 * <p>
 * Use this supplement when a subject is created or driven by an external workflow
 * system (e.g. a {@code quarkus-flow} workflow instance). The three source fields
 * identify the source entity precisely enough to correlate across systems:
 *
 * <pre>{@code
 * ProvenanceSupplement ps = new ProvenanceSupplement();
 * ps.sourceEntityId = workflowInstance.id.toString();
 * ps.sourceEntityType = "Flow:WorkflowInstance";
 * ps.sourceEntitySystem = "quarkus-flow";
 * entry.attach(ps);
 * }</pre>
 *
 * <p>
 * For LLM agent entries, also populate {@code agentConfigHash} with the SHA-256
 * of the agent's configuration (e.g. CLAUDE.md + system prompts) to enable
 * configuration drift detection within a persona version. This field does not
 * affect trust scoring — it is a forensic audit field only. See ADR 0004.
 */
public class ProvenanceSupplement extends LedgerSupplement {

    /** Identifier of the external entity that originated this subject. */
    public String sourceEntityId;

    /**
     * Type of the external entity.
     * Convention: {@code "System:TypeName"}, e.g. {@code "Flow:WorkflowInstance"}.
     */
    public String sourceEntityType;

    /**
     * The system that owns the external entity.
     * Example: {@code "quarkus-flow"}, {@code "quarkus-tarkus"}.
     */
    public String sourceEntitySystem;

    /**
     * SHA-256 hex digest of the LLM agent's configuration at session start
     * (e.g. {@code sha256(CLAUDE.md + system-prompts)}). Nullable — only populated
     * for entries produced by LLM agents. Used for configuration drift detection;
     * not the trust key (trust accumulates on {@code actorId}). See ADR 0004.
     */
    public String agentConfigHash;
}
