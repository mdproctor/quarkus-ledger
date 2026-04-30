package io.casehub.ledger.examples.privacy.api;

import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.casehub.ledger.examples.privacy.service.CreditApplicationService;
import io.casehub.ledger.runtime.model.LedgerEntry;
import io.casehub.ledger.runtime.privacy.LedgerErasureService.ErasureResult;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;

/**
 * REST endpoints for the credit application privacy demonstration.
 *
 * <ul>
 * <li>{@code POST /applications/analyse} — AI agent analyses a credit application</li>
 * <li>{@code POST /applications/review} — Human officer reviews a referred application</li>
 * <li>{@code POST /erasure/{actorId}} — GDPR Art.17 erasure request</li>
 * <li>{@code GET /applications/{applicationId}/ledger} — audit trail for an application</li>
 * </ul>
 */
@Path("/applications")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CreditApplicationResource {

    @Inject
    CreditApplicationService service;

    @Inject
    LedgerEntryRepository repo;

    // ── Request / response records ────────────────────────────────────────────

    public record AnalyseRequest(UUID applicationId, String applicantId, double riskScore) {
    }

    public record AnalyseResponse(UUID entryId) {
    }

    public record ReviewRequest(UUID applicationId, String officerId, boolean approved) {
    }

    public record LedgerEntryView(UUID id, String actorId, String actorRole, String actorType,
            String entryType, String supplementJson) {
    }

    // ── Endpoints ─────────────────────────────────────────────────────────────

    /**
     * AI risk agent analyses a credit application.
     * The agent's persona name is pseudonymised on write.
     */
    @POST
    @Path("/analyse")
    @Transactional
    public Response analyse(final AnalyseRequest req) {
        final UUID entryId = service.analyseApplication(
                req.applicationId(), req.applicantId(), req.riskScore());
        return Response.status(Response.Status.CREATED)
                .entity(new AnalyseResponse(entryId))
                .build();
    }

    /**
     * Human risk officer reviews a referred application.
     * The officer's raw identity is pseudonymised on write.
     */
    @POST
    @Path("/review")
    @Transactional
    public Response review(final ReviewRequest req) {
        service.humanReview(req.applicationId(), req.officerId(), req.approved());
        return Response.status(Response.Status.CREATED).build();
    }

    /**
     * GDPR Art.17 erasure — severs the token→identity mapping for the given actor.
     * The audit record survives; the raw identity becomes unresolvable.
     * No request body — actor ID is in the path.
     */
    @POST
    @Path("/erasure/{actorId}")
    @Consumes(MediaType.WILDCARD)
    public ErasureResult erase(@PathParam("actorId") final String actorId) {
        return service.erase(actorId);
    }

    /**
     * Returns the full audit trail for a credit application.
     * After erasure, actorId fields are UUID tokens with no resolvable mapping.
     */
    @GET
    @Path("/{applicationId}/ledger")
    public List<LedgerEntryView> ledger(@PathParam("applicationId") final UUID applicationId) {
        return repo.findBySubjectId(applicationId).stream()
                .map(e -> new LedgerEntryView(
                        e.id,
                        e.actorId,
                        e.actorRole,
                        e.actorType != null ? e.actorType.name() : null,
                        e.entryType != null ? e.entryType.name() : null,
                        e.supplementJson))
                .toList();
    }
}
