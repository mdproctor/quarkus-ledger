package io.casehub.ledger.examples.art22.ledger;

import jakarta.enterprise.context.ApplicationScoped;

import io.casehub.ledger.runtime.repository.jpa.JpaLedgerEntryRepository;

/**
 * Typed repository for {@link DecisionLedgerEntry}.
 *
 * <p>
 * Extends {@link JpaLedgerEntryRepository} to inherit all SPI implementations.
 *
 * <p>
 * {@code JpaLedgerEntryRepository} is {@code @Alternative} — this subclass
 * activates it and wins without any extra CDI configuration.
 */
@ApplicationScoped
public class DecisionLedgerEntryRepository extends JpaLedgerEntryRepository {
    // Inherits all SPI implementations
}
