package io.quarkiverse.ledger.runtime.service.routing;

import java.time.Instant;

public record TrustScoreComputedAt(Instant computedAt, int actorCount) {
}
