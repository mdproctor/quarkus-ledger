package io.quarkiverse.ledger.examples.routing.api;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.quarkiverse.ledger.examples.routing.routing.TaskRouter;

@Path("/routing")
@Produces(MediaType.APPLICATION_JSON)
public class TaskRoutingResource {

    @Inject
    TaskRouter taskRouter;

    @GET
    @Path("/ranked-agents")
    public List<String> rankedAgents() {
        return taskRouter.getRankedAgents();
    }
}
