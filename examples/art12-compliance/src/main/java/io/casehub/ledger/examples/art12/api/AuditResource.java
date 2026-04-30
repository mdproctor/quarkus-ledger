package io.casehub.ledger.examples.art12.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.casehub.ledger.examples.art12.service.AuditService;
import io.casehub.ledger.runtime.model.LedgerEntry;
import io.casehub.ledger.runtime.model.supplement.ComplianceSupplement;

/**
 * REST API for the art12-compliance example.
 *
 * <p>
 * Endpoints:
 * <ul>
 * <li>{@code POST /decisions} — record an AI decision with Art.12 compliance supplement</li>
 * <li>{@code GET /decisions/audit?actorId=X&from=Y&to=Z} — audit by actor and time range</li>
 * <li>{@code GET /decisions/range?from=Y&to=Z} — bulk export by time range</li>
 * </ul>
 */
@Path("/decisions")
@Produces(MediaType.APPLICATION_JSON)
public class AuditResource {

    @Inject
    AuditService auditService;

    @POST
    public Response record(
            @QueryParam("actorId") final String actorId,
            @QueryParam("category") final String category,
            @QueryParam("algorithm") final String algorithm,
            @QueryParam("confidence") @DefaultValue("0.9") final double confidence) {
        final var e = auditService.recordDecision(actorId, category, algorithm, confidence);
        return Response.status(201).entity(Map.of("id", e.id.toString(), "actorId", e.actorId)).build();
    }

    @GET
    @Path("/audit")
    public List<Map<String, Object>> auditByActor(
            @QueryParam("actorId") final String actorId,
            @QueryParam("from") final String from,
            @QueryParam("to") final String to) {
        return auditService.auditByActor(actorId, Instant.parse(from), Instant.parse(to))
                .stream().map(this::toView).collect(Collectors.toList());
    }

    @GET
    @Path("/range")
    public List<Map<String, Object>> auditByRange(
            @QueryParam("from") final String from,
            @QueryParam("to") final String to) {
        return auditService.auditByTimeRange(Instant.parse(from), Instant.parse(to))
                .stream().map(this::toView).collect(Collectors.toList());
    }

    private Map<String, Object> toView(final LedgerEntry e) {
        final ComplianceSupplement cs = e.compliance().orElse(null);
        return Map.of(
                "id", String.valueOf(e.id),
                "actorId", e.actorId != null ? e.actorId : "",
                "occurredAt", e.occurredAt != null ? e.occurredAt.toString() : "",
                "algorithmRef", cs != null && cs.algorithmRef != null ? cs.algorithmRef : "",
                "confidence", cs != null && cs.confidenceScore != null ? cs.confidenceScore : 0.0);
    }
}
