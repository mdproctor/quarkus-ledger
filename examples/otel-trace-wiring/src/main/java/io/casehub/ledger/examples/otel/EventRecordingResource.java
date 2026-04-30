package io.casehub.ledger.examples.otel;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

import io.casehub.ledger.runtime.model.ActorType;
import io.casehub.ledger.runtime.model.LedgerEntryType;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;

/**
 * REST resource for recording events into the ledger.
 *
 * <p>
 * Each inbound HTTP request carries an OTel server span created automatically by
 * Quarkus. When {@link LedgerEntryRepository#save(io.casehub.ledger.runtime.model.LedgerEntry)}
 * triggers JPA persist, {@link io.casehub.ledger.runtime.service.LedgerTraceListener}
 * reads the active span via {@link io.casehub.ledger.runtime.service.OtelTraceIdProvider}
 * and populates {@code LedgerEntry.traceId} — no code at this call site is required.
 */
@Path("/events")
@Produces(APPLICATION_JSON)
public class EventRecordingResource {

    @Inject
    LedgerEntryRepository repo;

    @Inject
    EntityManager em;

    record EventRequest(String name, String actorId) {}

    record EventResponse(UUID id, String traceId) {}

    /**
     * Records a named event. The {@code traceId} in the response is auto-populated
     * from the active OTel span — no explicit wiring required.
     */
    @POST
    @Consumes(APPLICATION_JSON)
    @Transactional
    public Response recordEvent(EventRequest request) {
        if (request == null || request.name() == null || request.actorId() == null) {
            return Response.status(400).build();
        }
        RecordedEventLedgerEntry entry = new RecordedEventLedgerEntry();
        entry.subjectId = UUID.randomUUID();
        entry.sequenceNumber = 1;
        entry.entryType = LedgerEntryType.EVENT;
        entry.actorId = request.actorId();
        entry.actorType = ActorType.HUMAN;
        entry.actorRole = "EventProducer";
        entry.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        entry.eventName = request.name();

        repo.save(entry);
        // traceId was auto-populated by LedgerTraceListener — no code needed here

        return Response.status(201).entity(new EventResponse(entry.id, entry.traceId)).build();
    }

    /**
     * Retrieves a previously recorded event by ID. Demonstrates that the {@code traceId}
     * is persisted to the database and survives a round-trip.
     */
    @GET
    @Path("/{id}")
    public Response getEvent(@PathParam("id") UUID id) {
        RecordedEventLedgerEntry entry = em.find(RecordedEventLedgerEntry.class, id);
        if (entry == null) {
            return Response.status(404).build();
        }
        return Response.ok(new EventResponse(entry.id, entry.traceId)).build();
    }
}
