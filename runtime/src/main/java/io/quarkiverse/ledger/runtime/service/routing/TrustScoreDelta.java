package io.quarkiverse.ledger.runtime.service.routing;

public record TrustScoreDelta(
        String actorId,
        double previousScore,
        double newScore,
        double previousGlobalScore,
        double newGlobalScore) {
}
