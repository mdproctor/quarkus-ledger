package io.casehub.ledger.examples.order.api;

import java.math.BigDecimal;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.casehub.ledger.examples.order.service.OrderService;
import io.quarkus.runtime.StartupEvent;

/**
 * Seeds sample orders in dev and test modes so the example is immediately explorable.
 *
 * <p>
 * Disabled in production via the {@code %prod} profile property
 * {@code app.sample-data.enabled=false}.
 */
@ApplicationScoped
public class SampleDataLoader {

    @Inject
    OrderService orderService;

    @Transactional
    void onStart(@Observes final StartupEvent event) {
        // Quarkus injects the active profile — guard via application.properties
        // %prod.app.sample-data.enabled=false
        // This bean is @ApplicationScoped and fires regardless; the property
        // guard is in application.properties (enabled=false in prod).
        if (!Boolean.parseBoolean(
                io.quarkus.runtime.configuration.ConfigUtils
                        .isPropertyPresent("app.sample-data.enabled")
                                ? System.getProperty("app.sample-data.enabled", "true")
                                : "true")) {
            return;
        }
        seedSampleData();
    }

    private void seedSampleData() {
        // Order 1: fully delivered — four ledger entries, full hash chain
        final var o1 = orderService.placeOrder("alice", new BigDecimal("129.99"));
        orderService.shipOrder(o1.id, "warehouse-team");
        orderService.deliverOrder(o1.id, "courier-42");

        // Order 2: placed and shipped — two ledger entries
        final var o2 = orderService.placeOrder("bob", new BigDecimal("49.50"));
        orderService.shipOrder(o2.id, "warehouse-team");

        // Order 3: cancelled — two ledger entries (place + cancel with rationale)
        final var o3 = orderService.placeOrder("carol", new BigDecimal("299.00"));
        orderService.cancelOrder(o3.id, "carol", "Found a better price elsewhere");
    }
}
