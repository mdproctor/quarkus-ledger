package io.quarkiverse.ledger.examples.order.ledger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import io.quarkiverse.ledger.runtime.repository.jpa.JpaLedgerEntryRepository;

/**
 * Typed repository for {@link OrderLedgerEntry}.
 *
 * <p>
 * Extends {@link JpaLedgerEntryRepository} to inherit all SPI implementations.
 * Domain-specific methods use an injected {@link EntityManager} directly.
 *
 * <p>
 * {@code JpaLedgerEntryRepository} is {@code @Alternative} — this subclass
 * activates it and wins without any extra CDI configuration.
 */
@ApplicationScoped
public class OrderLedgerEntryRepository extends JpaLedgerEntryRepository {

    @Inject
    EntityManager orderEm;

    public List<OrderLedgerEntry> findByOrderId(final UUID orderId) {
        return orderEm.createQuery(
                "SELECT e FROM OrderLedgerEntry e WHERE e.subjectId = :sid ORDER BY e.sequenceNumber ASC",
                OrderLedgerEntry.class)
                .setParameter("sid", orderId)
                .getResultList();
    }

    public Optional<OrderLedgerEntry> findLatestByOrderId(final UUID orderId) {
        return orderEm.createQuery(
                "SELECT e FROM OrderLedgerEntry e WHERE e.subjectId = :sid ORDER BY e.sequenceNumber DESC",
                OrderLedgerEntry.class)
                .setParameter("sid", orderId)
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }
}
