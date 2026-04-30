package io.casehub.ledger.example.merkle;

import jakarta.enterprise.context.ApplicationScoped;

import io.casehub.ledger.runtime.repository.jpa.JpaLedgerEntryRepository;

/**
 * CDI bean providing the {@link io.casehub.ledger.runtime.repository.LedgerEntryRepository}
 * SPI for the merkle-verification example.
 *
 * <p>
 * Extends {@link JpaLedgerEntryRepository} to inherit all SPI implementations.
 * Activated via {@code quarkus.arc.selected-alternatives} in application.properties —
 * required because {@link JpaLedgerEntryRepository} is {@code @Alternative}.
 */
@ApplicationScoped
public class AuditEntryRepository extends JpaLedgerEntryRepository {
    // Inherits all SPI implementations
}
