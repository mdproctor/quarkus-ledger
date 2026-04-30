package io.casehub.ledger.runtime.service.routing;

import java.util.List;

import io.casehub.ledger.runtime.model.ActorTrustScore;

public record TrustScoreFullPayload(List<ActorTrustScore> scores) {
}
