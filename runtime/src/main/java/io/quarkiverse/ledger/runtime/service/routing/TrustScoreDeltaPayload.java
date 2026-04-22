package io.quarkiverse.ledger.runtime.service.routing;

import java.util.List;

public record TrustScoreDeltaPayload(List<TrustScoreDelta> deltas) {
}
