package io.quarkiverse.ledger.example.prov;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import io.quarkiverse.ledger.runtime.model.LedgerEntry;

@Entity
@Table(name = "prov_example_entry")
@DiscriminatorValue("PROV_EXAMPLE")
public class ProvAuditEntry extends LedgerEntry {}
