package io.casehub.ledger.examples.art12.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import io.casehub.ledger.runtime.model.LedgerEntry;

@Entity
@Table(name = "art12_decision_entry")
@DiscriminatorValue("ART12_DECISION")
public class DecisionEntry extends LedgerEntry {

    @Column(name = "decision_category", length = 100)
    public String decisionCategory;
}
