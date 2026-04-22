package io.quarkiverse.ledger.examples.routing.routing;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;

import org.jboss.logging.Logger;

import io.quarkiverse.ledger.runtime.service.routing.TrustScoreComputedAt;

@ApplicationScoped
public class RoutingSignalLogger {

    private static final Logger log = Logger.getLogger(RoutingSignalLogger.class);

    public static volatile CountDownLatch notifyLatch = new CountDownLatch(1);

    public CompletionStage<Void> onNotification(
            @ObservesAsync final TrustScoreComputedAt notification) {
        log.infof("Trust scores refreshed at %s for %d actors",
                notification.computedAt(), notification.actorCount());
        notifyLatch.countDown();
        return CompletableFuture.completedFuture(null);
    }

    public static void resetLatch() {
        notifyLatch = new CountDownLatch(1);
    }
}
