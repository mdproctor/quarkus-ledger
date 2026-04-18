package io.quarkiverse.ledger.examples.art22.api;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.quarkiverse.ledger.examples.art22.ledger.DecisionLedgerEntry;
import io.quarkiverse.ledger.examples.art22.service.DecisionService;
import io.quarkiverse.ledger.runtime.model.supplement.ComplianceSupplement;
import io.quarkiverse.ledger.runtime.service.LedgerMerkleTree;

@Path("/decisions")
@Produces(MediaType.APPLICATION_JSON)
public class DecisionResource {

    @Inject
    DecisionService decisionService;

    @POST
    public Response record(final DecisionRequest req) {
        final DecisionLedgerEntry entry = decisionService.recordDecision(
                req.subjectId, req.category, req.outcome,
                req.algorithmRef, req.confidence, req.inputContext);
        return Response.status(201).entity(toView(entry)).build();
    }

    @GET
    @Path("/{subjectId}/ledger")
    public List<Map<String, Object>> ledger(@PathParam("subjectId") final String subjectId) {
        return decisionService.history(subjectId).stream()
                .map(this::toView).toList();
    }

    @GET
    @Path("/{subjectId}/ledger/verify")
    public Map<String, Object> verify(@PathParam("subjectId") final String subjectId) {
        final List<DecisionLedgerEntry> entries = decisionService.history(subjectId);
        final boolean intact = entries.stream()
                .allMatch(e -> e.digest != null && e.digest.equals(LedgerMerkleTree.leafHash(e)));
        return Map.of("intact", intact, "entries", entries.size());
    }

    private Map<String, Object> toView(final DecisionLedgerEntry e) {
        final ComplianceSupplement cs = e.compliance().orElse(null);
        return Map.of(
                "id", e.id,
                "decisionId", e.decisionId,
                "category", e.decisionCategory,
                "outcome", e.outcome,
                "actorId", e.actorId,
                "occurredAt", e.occurredAt,
                "digest", e.digest != null ? e.digest : "",
                "supplementJson", e.supplementJson != null ? e.supplementJson : "",
                "art22", cs == null ? Map.of() : Map.of(
                        "algorithmRef", cs.algorithmRef != null ? cs.algorithmRef : "",
                        "confidenceScore", cs.confidenceScore != null ? cs.confidenceScore : 0.0,
                        "contestationUri", cs.contestationUri != null ? cs.contestationUri : "",
                        "humanOverrideAvailable", Boolean.TRUE.equals(cs.humanOverrideAvailable),
                        // GDPR Arts.13-15: meaningful information about inputs used
                        "decisionContext", cs.decisionContext != null ? cs.decisionContext : ""));
    }

    public static class DecisionRequest {
        public String subjectId;
        public String category;
        public String outcome;
        public String algorithmRef;
        public double confidence;
        public String inputContext;
    }
}
