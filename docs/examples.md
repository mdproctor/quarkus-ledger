# Examples

Complete worked example — order processing domain.

This example shows `quarkus-ledger` used in a Quarkus application that tracks an order lifecycle. Every state transition — placement, payment, fulfilment, cancellation — becomes an immutable ledger entry with hash-chain tamper evidence and optional peer attestation.

---

## Entity: OrderLedgerEntry

```java
package com.example.order.ledger;

import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import jakarta.persistence.*;
import java.util.UUID;

/**
 * Ledger entry for the Order aggregate.
 *
 * subjectId (inherited) = orderId — scopes sequence numbering and hash chain per order.
 */
@Entity
@Table(name = "order_ledger_entry")
@DiscriminatorValue("ORDER")
public class OrderLedgerEntry extends LedgerEntry {

    /** The Order this entry belongs to — mirrors subjectId with a typed field. */
    @Column(name = "order_id", nullable = false)
    public UUID orderId;

    /** The actor's declared intent — e.g. "PlaceOrder", "CancelOrder". */
    @Column(name = "command_type")
    public String commandType;

    /** The observable fact after execution — e.g. "OrderPlaced", "OrderCancelled". */
    @Column(name = "event_type")
    public String eventType;

    /** The order status after this transition. */
    @Column(name = "order_status")
    public String orderStatus;
}
```

---

## Flyway migration

```sql
-- V1003__order_ledger_entry.sql
CREATE TABLE order_ledger_entry (
    id           UUID         NOT NULL,
    order_id     UUID         NOT NULL,
    command_type VARCHAR(100),
    event_type   VARCHAR(100),
    order_status VARCHAR(50),
    CONSTRAINT pk_order_ledger_entry PRIMARY KEY (id),
    CONSTRAINT fk_order_ledger_entry FOREIGN KEY (id) REFERENCES ledger_entry (id)
);

CREATE INDEX idx_ole_order_id ON order_ledger_entry (order_id);
```

---

## Repository

```java
package com.example.order.ledger;

import io.quarkiverse.ledger.runtime.model.*;
import io.quarkiverse.ledger.runtime.repository.LedgerEntryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class OrderLedgerEntryRepository implements LedgerEntryRepository {

    // Typed convenience methods

    public List<OrderLedgerEntry> findByOrderId(UUID orderId) {
        return OrderLedgerEntry.list(
            "subjectId = ?1 ORDER BY sequenceNumber ASC", orderId);
    }

    public Optional<OrderLedgerEntry> findLatestByOrderId(UUID orderId) {
        return OrderLedgerEntry
            .find("subjectId = ?1 ORDER BY sequenceNumber DESC", orderId)
            .firstResultOptional();
    }

    // LedgerEntryRepository SPI

    @Override public LedgerEntry save(LedgerEntry e) { e.persist(); return e; }

    @Override
    public List<LedgerEntry> findBySubjectId(UUID id) {
        return LedgerEntry.list("subjectId = ?1 ORDER BY sequenceNumber ASC", id);
    }

    @Override
    public Optional<LedgerEntry> findLatestBySubjectId(UUID id) {
        return LedgerEntry.find("subjectId = ?1 ORDER BY sequenceNumber DESC", id)
                          .firstResultOptional();
    }

    @Override
    public Optional<LedgerEntry> findById(UUID id) {
        return Optional.ofNullable(LedgerEntry.findById(id));
    }

    @Override
    public List<LedgerAttestation> findAttestationsByEntryId(UUID entryId) {
        return LedgerAttestation.list("ledgerEntryId = ?1 ORDER BY occurredAt ASC", entryId);
    }

    @Override
    public LedgerAttestation saveAttestation(LedgerAttestation a) { a.persist(); return a; }

    @Override
    public List<LedgerEntry> findAllEvents() {
        return LedgerEntry.find("entryType = ?1", LedgerEntryType.EVENT).list();
    }

    @Override
    public Map<UUID, List<LedgerAttestation>> findAttestationsForEntries(Set<UUID> ids) {
        if (ids.isEmpty()) return Collections.emptyMap();
        return LedgerAttestation.<LedgerAttestation>list("ledgerEntryId IN ?1", ids)
            .stream().collect(Collectors.groupingBy(a -> a.ledgerEntryId));
    }
}
```

---

## Capture service

```java
package com.example.order.ledger;

import com.example.order.model.Order;
import io.quarkiverse.ledger.runtime.config.LedgerConfig;
import io.quarkiverse.ledger.runtime.model.*;
import io.quarkiverse.ledger.runtime.service.LedgerHashChain;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class OrderLedgerCapture {

    @Inject OrderLedgerEntryRepository repo;
    @Inject LedgerConfig config;

    private static final Map<String, String[]> EVENT_META = Map.of(
        "placed",    new String[]{"PlaceOrder",   "OrderPlaced",    "Customer"},
        "paid",      new String[]{"PayOrder",     "OrderPaid",      "PaymentSystem"},
        "shipped",   new String[]{"ShipOrder",    "OrderShipped",   "Fulfilment"},
        "delivered", new String[]{"DeliverOrder", "OrderDelivered", "Carrier"},
        "cancelled", new String[]{"CancelOrder",  "OrderCancelled", "Customer"}
    );

    @Transactional
    public void record(UUID orderId, String transition, String actor, Order currentState) {
        if (!config.enabled()) return;

        String[] meta = EVENT_META.getOrDefault(transition,
            new String[]{transition, transition + "Occurred", "System"});

        int nextSeq = repo.findLatestByOrderId(orderId)
                          .map(e -> e.sequenceNumber + 1)
                          .orElse(1);
        String previousHash = repo.findLatestByOrderId(orderId)
                                  .map(e -> e.digest)
                                  .orElse(null);

        OrderLedgerEntry entry = new OrderLedgerEntry();
        entry.subjectId      = orderId;
        entry.orderId        = orderId;
        entry.sequenceNumber = nextSeq;
        entry.entryType      = LedgerEntryType.EVENT;
        entry.commandType    = meta[0];
        entry.eventType      = meta[1];
        entry.actorId        = actor;
        entry.actorType      = ActorType.HUMAN;
        entry.actorRole      = meta[2];
        entry.orderStatus    = currentState.status.name();
        // Set before hash computation — @PrePersist is too late
        entry.occurredAt     = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        if (config.decisionContext().enabled()) {
            final ComplianceSupplement cs = new ComplianceSupplement();
            cs.decisionContext = String.format(
                "{\"status\":\"%s\",\"total\":%s,\"customerId\":\"%s\"}",
                currentState.status, currentState.total, currentState.customerId);
            entry.attach(cs);
        }

        if (config.hashChain().enabled()) {
            entry.previousHash = previousHash;
            entry.digest = LedgerHashChain.compute(previousHash, entry);
        }

        repo.save(entry);
    }
}
```

---

## Domain service (wiring it together)

```java
package com.example.order;

import com.example.order.ledger.OrderLedgerCapture;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.UUID;

@ApplicationScoped
public class OrderService {

    @Inject OrderRepository orderRepo;
    @Inject OrderLedgerCapture ledger;

    @Transactional
    public void placeOrder(UUID orderId, String customerId) {
        Order order = new Order(orderId, customerId, OrderStatus.PLACED);
        orderRepo.persist(order);
        ledger.record(orderId, "placed", customerId, order);
    }

    @Transactional
    public void shipOrder(UUID orderId, String fulfilmentAgent) {
        Order order = orderRepo.findById(orderId);
        order.status = OrderStatus.SHIPPED;
        ledger.record(orderId, "shipped", fulfilmentAgent, order);
    }

    @Transactional
    public void cancelOrder(UUID orderId, String actor, String reason) {
        Order order = orderRepo.findById(orderId);
        order.status = OrderStatus.CANCELLED;
        ledger.record(orderId, "cancelled", actor, order);
    }
}
```

---

## REST endpoint — retrieve audit trail

```java
package com.example.order.api;

import com.example.order.ledger.OrderLedgerEntry;
import com.example.order.ledger.OrderLedgerEntryRepository;
import io.quarkiverse.ledger.runtime.model.LedgerAttestation;
import io.quarkiverse.ledger.runtime.service.LedgerHashChain;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/orders/{id}/ledger")
@Produces(MediaType.APPLICATION_JSON)
public class OrderLedgerResource {

    @Inject OrderLedgerEntryRepository repo;

    /** Return the full audit trail for an order. */
    @GET
    public List<OrderLedgerEntry> getLedger(@PathParam("id") UUID orderId) {
        return repo.findByOrderId(orderId);
    }

    /** Verify chain integrity. Returns 200 with {intact: true/false}. */
    @GET
    @Path("/verify")
    public Map<String, Object> verifyChain(@PathParam("id") UUID orderId) {
        List<OrderLedgerEntry> entries = repo.findByOrderId(orderId);
        boolean intact = LedgerHashChain.verify(entries);
        return Map.of("intact", intact, "entryCount", entries.size());
    }

    /** Post a peer attestation on a specific ledger entry. */
    @POST
    @Path("/{entryId}/attestations")
    @Consumes(MediaType.APPLICATION_JSON)
    public void postAttestation(
            @PathParam("id") UUID orderId,
            @PathParam("entryId") UUID entryId,
            AttestationRequest req) {

        LedgerAttestation a = new LedgerAttestation();
        a.ledgerEntryId = entryId;
        a.subjectId     = orderId;
        a.attestorId    = req.attestorId;
        a.attestorType  = req.attestorType;
        a.verdict       = req.verdict;
        a.confidence    = req.confidence;
        repo.saveAttestation(a);
    }

    record AttestationRequest(
        String attestorId,
        io.quarkiverse.ledger.runtime.model.ActorType attestorType,
        io.quarkiverse.ledger.runtime.model.AttestationVerdict verdict,
        double confidence
    ) {}
}
```

---

## Test: verifying ledger entries

```java
package com.example.order.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import com.example.order.OrderService;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.service.LedgerHashChain;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@TestTransaction
class OrderLedgerTest {

    @Inject OrderService orderService;
    @Inject OrderLedgerEntryRepository ledgerRepo;

    @Test
    void placeOrder_createsLedgerEntry() {
        UUID orderId = UUID.randomUUID();
        orderService.placeOrder(orderId, "customer-1");

        List<OrderLedgerEntry> entries = ledgerRepo.findByOrderId(orderId);
        assertEquals(1, entries.size());

        OrderLedgerEntry e = entries.get(0);
        assertEquals("PlaceOrder", e.commandType);
        assertEquals("OrderPlaced", e.eventType);
        assertEquals("customer-1", e.actorId);
        assertEquals(LedgerEntryType.EVENT, e.entryType);
        assertEquals(orderId, e.subjectId);
        assertEquals(1, e.sequenceNumber);
        assertNotNull(e.digest);
    }

    @Test
    void fullLifecycle_hashChainIsValid() {
        UUID orderId = UUID.randomUUID();
        orderService.placeOrder(orderId, "customer-1");
        orderService.shipOrder(orderId, "fulfilment-agent");

        List<OrderLedgerEntry> entries = ledgerRepo.findByOrderId(orderId);
        assertEquals(2, entries.size());
        assertTrue(LedgerHashChain.verify(entries));
    }

    @Test
    void secondEntry_previousHashLinkedToFirst() {
        UUID orderId = UUID.randomUUID();
        orderService.placeOrder(orderId, "customer-1");
        orderService.shipOrder(orderId, "agent");

        List<OrderLedgerEntry> entries = ledgerRepo.findByOrderId(orderId);
        assertEquals(entries.get(0).digest, entries.get(1).previousHash);
    }

    @Test
    void cancelOrder_decisionContextCaptured() {
        UUID orderId = UUID.randomUUID();
        orderService.placeOrder(orderId, "customer-1");
        orderService.cancelOrder(orderId, "customer-1", "Changed my mind");

        List<OrderLedgerEntry> entries = ledgerRepo.findByOrderId(orderId);
        OrderLedgerEntry cancel = entries.get(1);
        // Decision context is stored in ComplianceSupplement, accessible via supplementJson
        assertNotNull(cancel.supplementJson);
        assertTrue(cancel.supplementJson.contains("CANCELLED"));
    }
}
```

---

## Real-world reference implementations

| Project | Subclass | Domain |
|---|---|---|
| [quarkus-tarkus](https://github.com/mdproctor/quarkus-tarkus) | `WorkItemLedgerEntry` | Task lifecycle — create, claim, start, complete, reject, delegate |
| [quarkus-qhorus](https://github.com/mdproctor/quarkus-qhorus) | `AgentMessageLedgerEntry` | AI agent telemetry — tool calls with duration, token count, context refs |

Both include full integration test suites that exercise the hash chain, sequence numbering, decision context, attestations, and trust score computation.
