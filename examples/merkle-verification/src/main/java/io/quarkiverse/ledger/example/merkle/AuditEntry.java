package io.casehub.ledger.example.merkle;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import io.casehub.ledger.runtime.model.LedgerEntry;

@Entity
@Table(name = "merkle_example_entry")
@DiscriminatorValue("MERKLE_EXAMPLE")
public class AuditEntry extends LedgerEntry {}
