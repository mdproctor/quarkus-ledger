package io.quarkiverse.ledger.examples.routing.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.quarkiverse.ledger.runtime.service.routing.TrustScoreFullPayload;

@ApplicationScoped
public class TaskRouter {

    private volatile List<String> rankedAgents = List.of();

    public void onScoresUpdated(@Observes final TrustScoreFullPayload payload) {
        rankedAgents = payload.scores().stream()
                .sorted(Comparator.comparingDouble(s -> -s.trustScore))
                .map(s -> s.actorId)
                .toList();
    }

    public List<String> getRankedAgents() {
        return new ArrayList<>(rankedAgents);
    }
}
