package io.quarkiverse.ledger.runtime.service.routing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.quarkiverse.ledger.runtime.config.LedgerConfig;
import io.quarkiverse.ledger.runtime.model.ActorTrustScore;

@ApplicationScoped
public class TrustScoreRoutingPublisher {

    private static final Logger log = Logger.getLogger(TrustScoreRoutingPublisher.class);

    @Inject
    Event<TrustScoreFullPayload> fullEvent;

    @Inject
    Event<TrustScoreDeltaPayload> deltaEvent;

    @Inject
    Event<TrustScoreComputedAt> notifyEvent;

    @Inject
    LedgerConfig config;

    @Inject
    BeanManager beanManager;

    private boolean hasFullObservers;
    private boolean hasDeltaObservers;
    private boolean hasNotifyObservers;

    @PostConstruct
    void detectObservers() {
        hasFullObservers = !beanManager
                .resolveObserverMethods(new TrustScoreFullPayload(List.of())).isEmpty();
        hasDeltaObservers = !beanManager
                .resolveObserverMethods(new TrustScoreDeltaPayload(List.of())).isEmpty();
        // resolveObserverMethods returns both @Observes and @ObservesAsync observers
        hasNotifyObservers = !beanManager
                .resolveObserverMethods(new TrustScoreComputedAt(Instant.EPOCH, 0)).isEmpty();
    }

    /** True when at least one TrustScoreDeltaPayload observer is registered. */
    public boolean needsPreviousSnapshot() {
        return hasDeltaObservers;
    }

    public void publish(final List<ActorTrustScore> current,
            final Map<String, ActorTrustScore> previousSnapshot,
            final Instant computedAt) {

        if (!config.trustScore().routingEnabled()) {
            return;
        }

        if (hasNotifyObservers) {
            final TrustScoreComputedAt notifyPayload = new TrustScoreComputedAt(computedAt, current.size());
            try {
                // fire() reaches @Observes (sync) observers
                notifyEvent.fire(notifyPayload);
            } catch (final Exception e) {
                log.warnf(e, "TrustScoreComputedAt sync observer failed — routing signal skipped");
            }
            try {
                // fireAsync() reaches @ObservesAsync (async) observers
                notifyEvent.fireAsync(notifyPayload)
                        .exceptionally(ex -> {
                            log.warnf(ex, "TrustScoreComputedAt async observer failed — routing signal skipped");
                            return null;
                        });
            } catch (final Exception e) {
                log.warnf(e, "TrustScoreComputedAt fireAsync failed — routing signal skipped");
            }
        }

        if (hasFullObservers) {
            try {
                fullEvent.fire(new TrustScoreFullPayload(List.copyOf(current)));
            } catch (final Exception e) {
                log.warnf(e, "TrustScoreFullPayload observer failed — routing signal skipped");
            }
        }

        if (hasDeltaObservers) {
            try {
                final double threshold = config.trustScore().routingDeltaThreshold();
                final List<TrustScoreDelta> deltas = computeDeltas(current, previousSnapshot, threshold);
                deltaEvent.fire(new TrustScoreDeltaPayload(deltas));
            } catch (final Exception e) {
                log.warnf(e, "TrustScoreDeltaPayload observer failed — routing signal skipped");
            }
        }
    }

    public static List<TrustScoreDelta> computeDeltas(
            final List<ActorTrustScore> current,
            final Map<String, ActorTrustScore> previousSnapshot,
            final double threshold) {

        final List<TrustScoreDelta> deltas = new ArrayList<>();
        for (final ActorTrustScore score : current) {
            final ActorTrustScore prev = previousSnapshot.get(score.actorId);
            final double prevTrust = prev != null ? prev.trustScore : 0.0;
            final double prevGlobal = prev != null ? prev.globalTrustScore : 0.0;
            if (Math.abs(score.trustScore - prevTrust) >= threshold) {
                deltas.add(new TrustScoreDelta(
                        score.actorId, prevTrust, score.trustScore,
                        prevGlobal, score.globalTrustScore));
            }
        }
        return deltas;
    }
}
