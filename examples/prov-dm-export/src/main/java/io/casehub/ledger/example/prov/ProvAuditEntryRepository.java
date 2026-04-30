package io.casehub.ledger.example.prov;

import jakarta.enterprise.context.ApplicationScoped;

import io.casehub.ledger.runtime.repository.jpa.JpaLedgerEntryRepository;

/**
 * CDI bean providing the {@link io.casehub.ledger.runtime.repository.LedgerEntryRepository}
 * SPI for the prov-dm-export example.
 *
 * <p>
 * Extends {@link JpaLedgerEntryRepository} to inherit all SPI implementations.
 * Activated via {@code quarkus.arc.selected-alternatives} in application.properties —
 * required because {@link JpaLedgerEntryRepository} is {@code @Alternative}.
 */
@ApplicationScoped
public class ProvAuditEntryRepository extends JpaLedgerEntryRepository {
    // Inherits all SPI implementations
}
