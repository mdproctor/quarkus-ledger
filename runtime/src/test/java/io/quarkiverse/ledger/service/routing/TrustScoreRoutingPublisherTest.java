package io.casehub.ledger.service.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.runtime.model.ActorTrustScore;
import io.casehub.ledger.runtime.service.routing.TrustScoreDelta;
import io.casehub.ledger.runtime.service.routing.TrustScoreRoutingPublisher;

class TrustScoreRoutingPublisherTest {

    // ── Fixture ───────────────────────────────────────────────────────────────

    private static ActorTrustScore score(final String actorId,
            final double trustScore, final double globalScore) {
        final ActorTrustScore s = new ActorTrustScore();
        s.actorId = actorId;
        s.trustScore = trustScore;
        s.globalTrustScore = globalScore;
        return s;
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void computeDeltas_firstRun_allActorsIncludedWithZeroPrevious() {
        final var current = List.of(
                score("agent-a", 0.7, 0.4),
                score("agent-b", 0.3, 0.1));
        final var previous = Map.<String, ActorTrustScore> of();

        final var deltas = TrustScoreRoutingPublisher.computeDeltas(current, previous, 0.01);

        assertThat(deltas).hasSize(2);
        assertThat(deltas).allSatisfy(d -> {
            assertThat(d.previousScore()).isEqualTo(0.0);
            assertThat(d.previousGlobalScore()).isEqualTo(0.0);
        });
    }

    @Test
    void computeDeltas_onlyChangedActorsIncluded() {
        final var current = List.of(
                score("agent-a", 0.8, 0.0),
                score("agent-b", 0.5, 0.0));
        final var previous = Map.of(
                "agent-a", score("agent-a", 0.5, 0.0),
                "agent-b", score("agent-b", 0.5, 0.0));

        final var deltas = TrustScoreRoutingPublisher.computeDeltas(current, previous, 0.01);

        assertThat(deltas).hasSize(1);
        assertThat(deltas.get(0).actorId()).isEqualTo("agent-a");
    }

    // ── Correctness ───────────────────────────────────────────────────────────

    @Test
    void computeDeltas_changeBelowThreshold_actorExcluded() {
        final var current = List.of(score("agent-a", 0.51, 0.0));
        final var previous = Map.of("agent-a", score("agent-a", 0.50, 0.0));

        final var deltas = TrustScoreRoutingPublisher.computeDeltas(current, previous, 0.02);

        assertThat(deltas).isEmpty();
    }

    @Test
    void computeDeltas_changeAtThreshold_actorIncluded() {
        final var current = List.of(score("agent-a", 0.52, 0.0));
        final var previous = Map.of("agent-a", score("agent-a", 0.50, 0.0));

        final var deltas = TrustScoreRoutingPublisher.computeDeltas(current, previous, 0.02);

        assertThat(deltas).hasSize(1);
        assertThat(deltas.get(0).previousScore()).isCloseTo(0.50, within(0.001));
        assertThat(deltas.get(0).newScore()).isCloseTo(0.52, within(0.001));
    }

    @Test
    void computeDeltas_noChange_emptyDeltas() {
        final var current = List.of(score("agent-a", 0.5, 0.0));
        final var previous = Map.of("agent-a", score("agent-a", 0.5, 0.0));

        final var deltas = TrustScoreRoutingPublisher.computeDeltas(current, previous, 0.01);

        assertThat(deltas).isEmpty();
    }

    @Test
    void computeDeltas_capturesPreviousAndNewGlobalScore() {
        final var current = List.of(score("agent-a", 0.8, 0.6));
        final var previous = Map.of("agent-a", score("agent-a", 0.5, 0.3));

        final var deltas = TrustScoreRoutingPublisher.computeDeltas(current, previous, 0.01);

        assertThat(deltas).hasSize(1);
        final TrustScoreDelta d = deltas.get(0);
        assertThat(d.previousGlobalScore()).isCloseTo(0.3, within(0.001));
        assertThat(d.newGlobalScore()).isCloseTo(0.6, within(0.001));
    }

    // ── Robustness ────────────────────────────────────────────────────────────

    @Test
    void computeDeltas_emptyCurrentScores_returnsEmpty() {
        final var deltas = TrustScoreRoutingPublisher.computeDeltas(
                List.of(), Map.of(), 0.01);

        assertThat(deltas).isEmpty();
    }

    @Test
    void computeDeltas_negativeScoreChange_belowThreshold_excluded() {
        final var current = List.of(score("agent-a", 0.495, 0.0));
        final var previous = Map.of("agent-a", score("agent-a", 0.5, 0.0));

        final var deltas = TrustScoreRoutingPublisher.computeDeltas(current, previous, 0.01);

        assertThat(deltas).isEmpty();
    }

    @Test
    void computeDeltas_negativeScoreChange_atThreshold_included() {
        final var current = List.of(score("agent-a", 0.49, 0.0));
        final var previous = Map.of("agent-a", score("agent-a", 0.5, 0.0));

        final var deltas = TrustScoreRoutingPublisher.computeDeltas(current, previous, 0.01);

        assertThat(deltas).hasSize(1);
        assertThat(deltas.get(0).newScore()).isCloseTo(0.49, within(0.001));
    }
}
