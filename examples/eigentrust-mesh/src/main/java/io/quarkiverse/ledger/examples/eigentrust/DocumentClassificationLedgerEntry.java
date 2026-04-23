package io.quarkiverse.ledger.examples.eigentrust;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import io.quarkiverse.ledger.runtime.model.LedgerEntry;

/**
 * Domain-specific ledger entry for document risk classification decisions.
 * Extends the base {@link LedgerEntry} via JPA JOINED inheritance.
 */
@Entity
@Table(name = "document_classification_ledger_entry")
public class DocumentClassificationLedgerEntry extends LedgerEntry {

    /** The document being classified. */
    @Column(name = "document_id")
    public UUID documentId;

    /** Risk level assigned by the classifying agent: "LOW", "MEDIUM", or "HIGH". */
    @Column(name = "risk_level")
    public String riskLevel;
}
