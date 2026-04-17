package io.quarkiverse.ledger.examples.art12.ledger;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.ledger.runtime.repository.jpa.JpaLedgerEntryRepository;

/**
 * CDI bean providing the {@link io.quarkiverse.ledger.runtime.repository.LedgerEntryRepository}
 * SPI for the art12-compliance example.
 *
 * <p>
 * Extends {@link JpaLedgerEntryRepository} to inherit all SPI implementations,
 * including the three audit query methods added for EU AI Act Art.12 compliance:
 * {@code findByActorId}, {@code findByActorRole}, and {@code findByTimeRange}.
 *
 * <p>
 * Activated via {@code quarkus.arc.selected-alternatives} in application.properties —
 * this is required because {@link JpaLedgerEntryRepository} is {@code @Alternative}.
 */
@ApplicationScoped
public class DecisionEntryRepository extends JpaLedgerEntryRepository {
    // Inherits all SPI implementations including the three new audit query methods
}
