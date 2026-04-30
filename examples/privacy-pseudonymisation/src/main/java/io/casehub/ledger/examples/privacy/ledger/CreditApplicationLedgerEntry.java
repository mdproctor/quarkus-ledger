package io.casehub.ledger.examples.privacy.ledger;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import io.casehub.ledger.runtime.model.LedgerEntry;

/**
 * Domain-specific ledger entry for credit application decisions.
 *
 * <p>
 * Uses JPA JOINED inheritance — this table stores only the credit-application-specific
 * columns; the common audit fields live in {@code ledger_entry}.
 */
@Entity
@Table(name = "credit_application_ledger_entry")
public class CreditApplicationLedgerEntry extends LedgerEntry {

    /** The credit application this entry belongs to. */
    @Column(name = "application_id", nullable = false)
    public UUID applicationId;

    /**
     * The type of decision recorded.
     * Examples: {@code "AnalyseApplication"}, {@code "ReviewDecision"}.
     */
    @Column(name = "decision_type")
    public String decisionType;

    /**
     * The outcome of the decision.
     * Examples: {@code "APPROVED"}, {@code "REFERRED"}, {@code "DECLINED"}.
     */
    @Column(name = "outcome")
    public String outcome;
}
