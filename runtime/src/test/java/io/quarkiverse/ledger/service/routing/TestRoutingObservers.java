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

    // Use lists accessed via methods — direct field access on a CDI proxy goes to
    // the proxy's fields, not the underlying bean's fields. Method calls are proxied.
    private final List<TrustScoreFullPayload> fullReceivedList = new CopyOnWriteArrayList<>();
    private final List<TrustScoreDeltaPayload> deltaReceivedList = new CopyOnWriteArrayList<>();
    private final List<TrustScoreComputedAt> notifyReceivedList = new CopyOnWriteArrayList<>();

    /** Use this accessor in tests — goes through the CDI proxy → underlying bean. */
    public List<TrustScoreFullPayload> fullReceived() {
        return fullReceivedList;
    }

    public List<TrustScoreDeltaPayload> deltaReceived() {
        return deltaReceivedList;
    }

    public List<TrustScoreComputedAt> notifyReceived() {
        return notifyReceivedList;
    }

    /** Reset between tests via @BeforeEach. */
    public static volatile CountDownLatch asyncLatch = new CountDownLatch(1);

    public void onFull(@Observes final TrustScoreFullPayload payload) {
        fullReceivedList.add(payload);
    }

    public void onDelta(@Observes final TrustScoreDeltaPayload payload) {
        deltaReceivedList.add(payload);
    }

    public void onNotify(@Observes final TrustScoreComputedAt payload) {
        notifyReceivedList.add(payload);
    }

    public CompletionStage<Void> onNotifyAsync(@ObservesAsync final TrustScoreComputedAt payload) {
        asyncLatch.countDown();
        return CompletableFuture.completedFuture(null);
    }

    public void reset() {
        fullReceivedList.clear();
        deltaReceivedList.clear();
        notifyReceivedList.clear();
        asyncLatch = new CountDownLatch(1);
    }
}
