package io.quarkiverse.ledger.service.routing;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;

import io.quarkiverse.ledger.runtime.service.routing.TrustScoreComputedAt;
import io.quarkiverse.ledger.runtime.service.routing.TrustScoreDeltaPayload;
import io.quarkiverse.ledger.runtime.service.routing.TrustScoreFullPayload;

@ApplicationScoped
public class TestRoutingObservers {

    public final List<TrustScoreFullPayload> fullReceived = new CopyOnWriteArrayList<>();
    public final List<TrustScoreDeltaPayload> deltaReceived = new CopyOnWriteArrayList<>();
    public final List<TrustScoreComputedAt> notifyReceived = new CopyOnWriteArrayList<>();

    /** Reset between tests via @BeforeEach. */
    public static volatile CountDownLatch asyncLatch = new CountDownLatch(1);

    public void onFull(@Observes final TrustScoreFullPayload payload) {
        fullReceived.add(payload);
    }

    public void onDelta(@Observes final TrustScoreDeltaPayload payload) {
        deltaReceived.add(payload);
    }

    public void onNotify(@Observes final TrustScoreComputedAt payload) {
        notifyReceived.add(payload);
    }

    public CompletionStage<Void> onNotifyAsync(@ObservesAsync final TrustScoreComputedAt payload) {
        asyncLatch.countDown();
        return CompletableFuture.completedFuture(null);
    }

    public void reset() {
        fullReceived.clear();
        deltaReceived.clear();
        notifyReceived.clear();
        asyncLatch = new CountDownLatch(1);
    }
}
