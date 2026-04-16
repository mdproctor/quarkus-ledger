package io.quarkiverse.ledger.runtime.model.supplement;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Supplement carrying workflow provenance — the external entity that originated
 * this ledger entry's subject.
 *
 * <p>
 * Use this supplement when a subject is created or driven by an external workflow
 * system (e.g. a {@code quarkus-flow} workflow instance). The three fields together
 * identify the source entity precisely enough to correlate across systems:
 *
 * <pre>{@code
 * ProvenanceSupplement ps = new ProvenanceSupplement();
 * ps.sourceEntityId     = workflowInstance.id.toString();
 * ps.sourceEntityType   = "Flow:WorkflowInstance";
 * ps.sourceEntitySystem = "quarkus-flow";
 * entry.attach(ps);
 * }</pre>
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
     * Example: {@code "quarkus-flow"}, {@code "quarkus-tarkus"}.
     */
    @Column(name = "source_entity_system", length = 100)
    public String sourceEntitySystem;
}
