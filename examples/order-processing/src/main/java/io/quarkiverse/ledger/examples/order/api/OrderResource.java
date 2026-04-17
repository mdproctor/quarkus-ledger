package io.quarkiverse.ledger.examples.order.api;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import io.quarkiverse.ledger.examples.order.ledger.OrderLedgerEntry;
import io.quarkiverse.ledger.examples.order.ledger.OrderLedgerEntryRepository;
import io.quarkiverse.ledger.examples.order.model.Order;
import io.quarkiverse.ledger.examples.order.service.OrderService;
import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.model.AttestationVerdict;
import io.quarkiverse.ledger.runtime.model.LedgerAttestation;
import io.quarkiverse.ledger.runtime.service.LedgerHashChain;

/**
 * REST API for the order-processing example.
 *
 * <p>
 * Try it with {@code mvn quarkus:dev} then:
 *
 * <pre>
 *   # Place an order
 *   curl -s -X POST http://localhost:8080/orders \
 *     -H 'Content-Type: application/json' \
 *     -d '{"customerId":"alice","total":49.99}' | jq .
 *
 *   # Ship it (replace &lt;id&gt; with the returned UUID)
 *   curl -s -X PUT "http://localhost:8080/orders/&lt;id&gt;/ship?actor=warehouse" | jq .
 *
 *   # View the full audit trail
 *   curl -s http://localhost:8080/orders/&lt;id&gt;/ledger | jq .
 *
 *   # Verify the hash chain
 *   curl -s http://localhost:8080/orders/&lt;id&gt;/ledger/verify | jq .
 * </pre>
 */
@Path("/orders")
@Produces(APPLICATION_JSON)
public class OrderResource {

    @Inject
    OrderService orderService;

    @Inject
    OrderLedgerEntryRepository ledgerRepo;

    // -------------------------------------------------------------------------
    // Order commands
    // -------------------------------------------------------------------------

    record PlaceOrderRequest(String customerId, BigDecimal total) {
    }

    @POST
    @Consumes(APPLICATION_JSON)
    @Transactional
    public Response placeOrder(final PlaceOrderRequest req) {
        final Order order = orderService.placeOrder(req.customerId(), req.total());
        return Response.status(Response.Status.CREATED).entity(order).build();
    }

    @PUT
    @Path("/{id}/ship")
    @Transactional
    public Order shipOrder(@PathParam("id") final UUID orderId,
            @QueryParam("actor") final String actor) {
        return orderService.shipOrder(orderId, actor != null ? actor : "system");
    }

    @PUT
    @Path("/{id}/deliver")
    @Transactional
    public Order deliverOrder(@PathParam("id") final UUID orderId,
            @QueryParam("actor") final String actor) {
        return orderService.deliverOrder(orderId, actor != null ? actor : "system");
    }

    @PUT
    @Path("/{id}/cancel")
    @Consumes(APPLICATION_JSON)
    @Transactional
    public Order cancelOrder(@PathParam("id") final UUID orderId,
            @QueryParam("actor") final String actor,
            @QueryParam("reason") final String reason) {
        return orderService.cancelOrder(orderId,
                actor != null ? actor : "system",
                reason != null ? reason : "");
    }

    // -------------------------------------------------------------------------
    // Order queries
    // -------------------------------------------------------------------------

    @GET
    public List<Order> listOrders() {
        return Order.listAll();
    }

    @GET
    @Path("/{id}")
    public Order getOrder(@PathParam("id") final UUID orderId) {
        return Order.findById(orderId);
    }

    // -------------------------------------------------------------------------
    // Ledger endpoints
    // -------------------------------------------------------------------------

    /**
     * Return the full audit trail for an order, with attestations embedded.
     */
    @GET
    @Path("/{id}/ledger")
    public List<LedgerEntryView> getLedger(@PathParam("id") final UUID orderId) {
        final List<OrderLedgerEntry> entries = ledgerRepo.findByOrderId(orderId);
        return entries.stream().map(e -> {
            final List<LedgerAttestation> attestations = ledgerRepo.findAttestationsByEntryId(e.id);
            return new LedgerEntryView(e, attestations);
        }).toList();
    }

    /**
     * Verify the SHA-256 hash chain for an order's ledger.
     *
     * <p>
     * Returns {@code {"intact": true, "entries": N}} when the chain is unmodified.
     * Returns {@code {"intact": false, ...}} if any entry has been tampered with.
     */
    @GET
    @Path("/{id}/ledger/verify")
    public Map<String, Object> verifyChain(@PathParam("id") final UUID orderId) {
        final List<OrderLedgerEntry> entries = ledgerRepo.findByOrderId(orderId);
        final boolean intact = LedgerHashChain.verify(entries);
        return Map.of(
                "intact", intact,
                "entries", entries.size(),
                "orderId", orderId);
    }

    /**
     * Post a peer attestation on a specific ledger entry.
     *
     * <p>
     * Example:
     *
     * <pre>
     *   curl -s -X POST http://localhost:8080/orders/&lt;id&gt;/ledger/&lt;entryId&gt;/attestations \
     *     -H 'Content-Type: application/json' \
     *     -d '{"attestorId":"compliance-bot","attestorType":"AGENT",
     *           "verdict":"SOUND","confidence":0.95}' | jq .
     * </pre>
     */
    @POST
    @Path("/{id}/ledger/{entryId}/attestations")
    @Consumes(APPLICATION_JSON)
    @Transactional
    public Response postAttestation(
            @PathParam("id") final UUID orderId,
            @PathParam("entryId") final UUID entryId,
            final AttestationRequest req) {

        final LedgerAttestation attestation = new LedgerAttestation();
        attestation.ledgerEntryId = entryId;
        attestation.subjectId = orderId;
        attestation.attestorId = req.attestorId();
        attestation.attestorType = req.attestorType();
        attestation.verdict = req.verdict();
        attestation.evidence = req.evidence();
        attestation.confidence = req.confidence();
        ledgerRepo.saveAttestation(attestation);

        return Response.status(Response.Status.CREATED).build();
    }

    // -------------------------------------------------------------------------
    // Audit queries
    // -------------------------------------------------------------------------

    /**
     * Return all ledger entries for a given actor within a time window.
     *
     * <p>
     * Example: {@code GET /orders/audit?actorId=alice&from=2020-01-01T00:00:00Z&to=2099-12-31T23:59:59Z}
     */
    @GET
    @Path("/audit")
    @Produces(APPLICATION_JSON)
    public List<Object> auditByActor(
            @QueryParam("actorId") final String actorId,
            @QueryParam("from") final String from,
            @QueryParam("to") final String to) {
        return ledgerRepo.findByActorId(actorId, Instant.parse(from), Instant.parse(to))
                .stream()
                .map(e -> (Object) Map.of(
                        "id", String.valueOf(e.id),
                        "actorId", e.actorId != null ? e.actorId : "",
                        "occurredAt", e.occurredAt != null ? e.occurredAt.toString() : ""))
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Response / request records
    // -------------------------------------------------------------------------

    record LedgerEntryView(
            UUID id,
            int sequenceNumber,
            String commandType,
            String eventType,
            String orderStatus,
            String actorId,
            String actorRole,
            String supplementJson,
            String previousHash,
            String digest,
            java.time.Instant occurredAt,
            List<LedgerAttestation> attestations) {

        LedgerEntryView(final OrderLedgerEntry e, final List<LedgerAttestation> attestations) {
            this(e.id, e.sequenceNumber, e.commandType, e.eventType, e.orderStatus,
                    e.actorId, e.actorRole, e.supplementJson,
                    e.previousHash, e.digest, e.occurredAt, attestations);
        }
    }

    record AttestationRequest(
            String attestorId,
            ActorType attestorType,
            AttestationVerdict verdict,
            String evidence,
            double confidence) {
    }
}
