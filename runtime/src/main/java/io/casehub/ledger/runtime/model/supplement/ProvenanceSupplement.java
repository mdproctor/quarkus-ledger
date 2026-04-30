package io.casehub.ledger.runtime.model.supplement;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

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
@Entity
@Table(name = "ledger_supplement_provenance")
@DiscriminatorValue("PROVENANCE")
public class ProvenanceSupplement extends LedgerSupplement {

    /** Identifier of the external entity that originated this subject. */
    @Column(name = "source_entity_id", length = 255)
    public String sourceEntityId;

    /**
     * Type of the external entity.
     * Convention: {@code "System:TypeName"}, e.g. {@code "Flow:WorkflowInstance"}.
     */
    @Column(name = "source_entity_type", length = 255)
    public String sourceEntityType;

    /**
     * The system that owns the external entity.
     * Example: {@code "quarkus-flow"}, {@code "casehub-work"}.
     */
    @Column(name = "source_entity_system", length = 100)
    public String sourceEntitySystem;

    /**
     * SHA-256 hex digest of the LLM agent's configuration at session start
     * (e.g. {@code sha256(CLAUDE.md + system-prompts)}). Nullable — only populated
     * for entries produced by LLM agents. Used for configuration drift detection;
     * not the trust key (trust accumulates on {@code actorId}). See ADR 0004.
     */
    @Column(name = "agent_config_hash", length = 64)
    public String agentConfigHash;
}
