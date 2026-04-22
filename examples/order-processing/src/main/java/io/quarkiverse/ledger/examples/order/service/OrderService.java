package io.quarkiverse.ledger.examples.order.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.quarkiverse.ledger.examples.order.ledger.OrderLedgerEntry;
import io.quarkiverse.ledger.examples.order.ledger.OrderLedgerEntryRepository;
import io.quarkiverse.ledger.examples.order.model.Order;
import io.quarkiverse.ledger.examples.order.model.OrderStatus;
import io.quarkiverse.ledger.runtime.config.LedgerConfig;
import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.model.supplement.ComplianceSupplement;

/**
 * Order domain service.
 *
 * <p>
 * Every state transition writes a corresponding {@link OrderLedgerEntry} in the
 * same transaction. If the transition rolls back, so does the ledger entry.
 */
@ApplicationScoped
public class OrderService {

    @Inject
    OrderLedgerEntryRepository ledgerRepo;

    @Inject
    LedgerConfig ledgerConfig;

    /** Command → event type mapping for each transition. */
    private static final Map<String, String[]> EVENT_META = Map.of(
            "place", new String[] { "PlaceOrder", "OrderPlaced", "Customer" },
            "ship", new String[] { "ShipOrder", "OrderShipped", "Fulfilment" },
            "deliver", new String[] { "DeliverOrder", "OrderDelivered", "Carrier" },
            "cancel", new String[] { "CancelOrder", "OrderCancelled", "Customer" });

    // -------------------------------------------------------------------------
    // Command methods
    // -------------------------------------------------------------------------

    @Transactional
    public Order placeOrder(final String customerId, final BigDecimal total) {
        final Order order = new Order();
        order.customerId = customerId;
        order.total = total;
        order.status = OrderStatus.PLACED;
        order.persist();

        if (ledgerConfig.enabled()) {
            record(order, "place", customerId);
        }
        return order;
    }

    @Transactional
    public Order shipOrder(final UUID orderId, final String actor) {
        final Order order = findOrThrow(orderId);
        order.status = OrderStatus.SHIPPED;

        if (ledgerConfig.enabled()) {
            record(order, "ship", actor);
        }
        return order;
    }

    @Transactional
    public Order deliverOrder(final UUID orderId, final String actor) {
        final Order order = findOrThrow(orderId);
        order.status = OrderStatus.DELIVERED;

        if (ledgerConfig.enabled()) {
            record(order, "deliver", actor);
        }
        return order;
    }

    @Transactional
    public Order cancelOrder(final UUID orderId, final String actor, final String reason) {
        final Order order = findOrThrow(orderId);
        order.status = OrderStatus.CANCELLED;

        if (ledgerConfig.enabled()) {
            final OrderLedgerEntry entry = record(order, "cancel", actor);
            // Attach or update ComplianceSupplement with rationale
            entry.compliance().ifPresentOrElse(
                    cs -> {
                        cs.rationale = reason;
                        entry.refreshSupplementJson(); // required after in-place field mutation
                    },
                    () -> {
                        final ComplianceSupplement cs = new ComplianceSupplement();
                        cs.rationale = reason;
                        entry.attach(cs);
                    });
        }
        return order;
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private Order findOrThrow(final UUID orderId) {
        return Optional.<Order> ofNullable(Order.findById(orderId))
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
    }

    private OrderLedgerEntry record(final Order order, final String transition, final String actor) {
        final String[] meta = EVENT_META.get(transition);

        final Optional<OrderLedgerEntry> latest = ledgerRepo.findLatestByOrderId(order.id);
        final int nextSeq = latest.map(e -> e.sequenceNumber + 1).orElse(1);

        final OrderLedgerEntry entry = new OrderLedgerEntry();
        entry.subjectId = order.id;
        entry.orderId = order.id;
        entry.sequenceNumber = nextSeq;
        entry.entryType = LedgerEntryType.EVENT;
        entry.commandType = meta[0];
        entry.eventType = meta[1];
        entry.actorId = actor;
        entry.actorType = ActorType.HUMAN;
        entry.actorRole = meta[2];
        entry.orderStatus = order.status.name();
        entry.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        if (ledgerConfig.decisionContext().enabled()) {
            final ComplianceSupplement cs = new ComplianceSupplement();
            cs.decisionContext = String.format(
                    "{\"status\":\"%s\",\"total\":%s,\"customerId\":\"%s\"}",
                    order.status, order.total, order.customerId);
            entry.attach(cs);
        }

        ledgerRepo.save(entry);

        return entry;
    }
}
