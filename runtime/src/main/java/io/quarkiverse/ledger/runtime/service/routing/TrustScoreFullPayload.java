package io.quarkiverse.ledger.runtime.service.routing;

import java.util.List;

import io.quarkiverse.ledger.runtime.model.ActorTrustScore;

public record TrustScoreFullPayload(List<ActorTrustScore> scores) {
}
