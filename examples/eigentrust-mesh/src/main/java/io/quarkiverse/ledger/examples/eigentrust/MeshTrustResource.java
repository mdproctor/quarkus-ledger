package io.casehub.ledger.examples.eigentrust;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.casehub.ledger.runtime.model.ActorTrustScore;

/**
 * REST facade for the EigenTrust mesh example.
 *
 * <p>
 * Typical call sequence:
 * <pre>
 *   POST /mesh/seed     — seed document classifications and peer attestations
 *   POST /mesh/compute  — run Bayesian Beta + EigenTrust computation
 *   GET  /mesh/scores   — retrieve all actor trust scores
 * </pre>
 */
@Path("/mesh")
@Produces(MediaType.APPLICATION_JSON)
public class MeshTrustResource {

    @Inject
    MeshTrustService meshTrustService;

    /** Seed the ledger with classification entries and peer attestations. */
    @POST
    @Path("/seed")
    public Response seed() {
        meshTrustService.seedClassifications();
        return Response.ok("{\"seeded\":true}").build();
    }

    /** Run TrustScoreJob — computes Bayesian Beta scores and EigenTrust global scores. */
    @POST
    @Path("/compute")
    public Response compute() {
        meshTrustService.runTrustComputation();
        return Response.ok("{\"computed\":true}").build();
    }

    /** Return all actor trust scores, including {@code trustScore} and {@code globalTrustScore}. */
    @GET
    @Path("/scores")
    public List<ScoreView> scores() {
        return meshTrustService.getScores().stream()
                .map(ScoreView::from)
                .toList();
    }

    /** JSON projection for a single actor's trust scores. */
    public record ScoreView(
            String actorId,
            double trustScore,
            double globalTrustScore,
            int decisionCount) {

        static ScoreView from(final ActorTrustScore s) {
            return new ScoreView(s.actorId, s.trustScore, s.globalTrustScore, s.decisionCount);
        }
    }
}
