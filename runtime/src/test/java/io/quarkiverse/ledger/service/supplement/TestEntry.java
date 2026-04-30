package io.casehub.ledger.service.supplement;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import io.casehub.ledger.runtime.model.LedgerEntry;

/**
 * Minimal concrete subclass of {@link LedgerEntry} for integration tests only.
 * Never used in production code.
 */
@Entity
@Table(name = "test_ledger_entry")
@DiscriminatorValue("TEST")
public class TestEntry extends LedgerEntry {
}
