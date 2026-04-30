package io.casehub.ledger.examples.art22.ledger;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import io.casehub.ledger.runtime.model.LedgerEntry;

/**
 * Domain-specific ledger entry for AI decisions.
 *
 * <p>
 * Extends the base {@link LedgerEntry} — the GDPR Art.22 compliance fields live in
 * a {@link io.casehub.ledger.runtime.model.supplement.ComplianceSupplement}
 * attached via {@link LedgerEntry#attach}.
 */
@Entity
@Table(name = "decision_ledger_entry")
@DiscriminatorValue("DECISION")
public class DecisionLedgerEntry extends LedgerEntry {

    @Column(name = "decision_id", nullable = false)
    public UUID decisionId;

    /** High-level category — e.g. {@code "credit-risk"}, {@code "content-moderation"}. */
    @Column(name = "decision_category", length = 100)
    public String decisionCategory;

    /** The decision outcome — e.g. {@code "APPROVED"}, {@code "FLAGGED"}, {@code "REJECTED"}. */
    @Column(name = "outcome", length = 50)
    public String outcome;
}
