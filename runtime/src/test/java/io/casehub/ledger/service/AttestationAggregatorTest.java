package io.casehub.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.runtime.model.LedgerAttestation;
import io.casehub.ledger.runtime.service.AttestationAggregator;
import io.casehub.ledger.runtime.service.AttestationAggregator.AggregatedAttestation;
import io.casehub.ledger.runtime.service.AttestationAggregator.Strategy;

class AttestationAggregatorTest {

    private final AttestationAggregator aggregator = new AttestationAggregator();

    // ── empty input ───────────────────────────────────────────────────────────

    @Test
    void empty_returnsEmpty_allStrategies() {
        assertThat(aggregator.aggregate(List.of(), Strategy.WEIGHTED_MAJORITY)).isEmpty();
        assertThat(aggregator.aggregate(List.of(), Strategy.UNANIMOUS_REQUIRED)).isEmpty();
        assertThat(aggregator.aggregate(List.of(), Strategy.FIRST_ATTESTOR)).isEmpty();
    }

    // ── single attestation pass-through ───────────────────────────────────────

    @Test
    void single_sound_passThrough() {
        final var result = aggregator.aggregate(List.of(attest(AttestationVerdict.SOUND, 0.8)), Strategy.WEIGHTED_MAJORITY);
        assertThat(result).isPresent();
        assertThat(result.get().consensusVerdict()).isEqualTo(AttestationVerdict.SOUND);
        assertThat(result.get().aggregatedConfidence()).isCloseTo(1.0, within(0.001));
        assertThat(result.get().attestorCount()).isEqualTo(1);
    }

    @Test
    void single_flagged_passThrough() {
        final var result = aggregator.aggregate(List.of(attest(AttestationVerdict.FLAGGED, 0.7)), Strategy.WEIGHTED_MAJORITY);
        assertThat(result.get().consensusVerdict()).isEqualTo(AttestationVerdict.FLAGGED);
        assertThat(result.get().aggregatedConfidence()).isCloseTo(1.0, within(0.001));
    }

    // ── WEIGHTED_MAJORITY: positive wins ─────────────────────────────────────

    @Test
    void weightedMajority_soundWins_twoSoundOneFlagged() {
        final var attestations = List.of(
                attest(AttestationVerdict.SOUND, 0.8),
                attest(AttestationVerdict.SOUND, 0.8),
                attest(AttestationVerdict.FLAGGED, 0.3));
        final var result = aggregator.aggregate(attestations, Strategy.WEIGHTED_MAJORITY);
        assertThat(result).isPresent();
        assertThat(result.get().consensusVerdict()).isEqualTo(AttestationVerdict.SOUND);
        assertThat(result.get().attestorCount()).isEqualTo(3);
    }

    @Test
    void weightedMajority_flaggedWins_higherConfidence() {
        // SOUND(0.5) vs FLAGGED(0.9): negative side wins
        final var attestations = List.of(
                attest(AttestationVerdict.SOUND, 0.5),
                attest(AttestationVerdict.FLAGGED, 0.9));
        final var result = aggregator.aggregate(attestations, Strategy.WEIGHTED_MAJORITY);
        assertThat(result.get().consensusVerdict()).isEqualTo(AttestationVerdict.FLAGGED);
    }

    @Test
    void weightedMajority_endorsed_countsAsPositive() {
        final var attestations = List.of(
                attest(AttestationVerdict.ENDORSED, 0.7),
                attest(AttestationVerdict.FLAGGED, 0.3));
        final var result = aggregator.aggregate(attestations, Strategy.WEIGHTED_MAJORITY);
        assertThat(result.get().consensusVerdict()).isEqualTo(AttestationVerdict.SOUND);
    }

    @Test
    void weightedMajority_challenged_countsAsNegative() {
        final var attestations = List.of(
                attest(AttestationVerdict.SOUND, 0.3),
                attest(AttestationVerdict.CHALLENGED, 0.7));
        final var result = aggregator.aggregate(attestations, Strategy.WEIGHTED_MAJORITY);
        assertThat(result.get().consensusVerdict()).isEqualTo(AttestationVerdict.FLAGGED);
    }

    @Test
    void weightedMajority_aggregatedConfidence_reflectsMargin() {
        // pos=0.8+0.8=1.6, neg=0.3 → margin=(1.6-0.3)/(1.6+0.3)=1.3/1.9≈0.684
        final var attestations = List.of(
                attest(AttestationVerdict.SOUND, 0.8),
                attest(AttestationVerdict.SOUND, 0.8),
                attest(AttestationVerdict.FLAGGED, 0.3));
        final var result = aggregator.aggregate(attestations, Strategy.WEIGHTED_MAJORITY);
        final double expectedConfidence = (1.6 - 0.3) / (1.6 + 0.3);
        assertThat(result.get().aggregatedConfidence()).isCloseTo(expectedConfidence, within(0.001));
    }

    // ── UNANIMOUS_REQUIRED ───────────────────────────────────────────────────

    @Test
    void unanimous_allSound_resultsSound() {
        final var attestations = List.of(
                attest(AttestationVerdict.SOUND, 0.9),
                attest(AttestationVerdict.SOUND, 0.7),
                attest(AttestationVerdict.ENDORSED, 0.8));
        final var result = aggregator.aggregate(attestations, Strategy.UNANIMOUS_REQUIRED);
        assertThat(result.get().consensusVerdict()).isEqualTo(AttestationVerdict.SOUND);
        // confidence = min of positive confidences
        assertThat(result.get().aggregatedConfidence()).isCloseTo(0.7, within(0.001));
    }

    @Test
    void unanimous_anyFlagged_resultsFlagged() {
        final var attestations = List.of(
                attest(AttestationVerdict.SOUND, 0.9),
                attest(AttestationVerdict.SOUND, 0.9),
                attest(AttestationVerdict.FLAGGED, 0.3));
        final var result = aggregator.aggregate(attestations, Strategy.UNANIMOUS_REQUIRED);
        assertThat(result.get().consensusVerdict()).isEqualTo(AttestationVerdict.FLAGGED);
    }

    @Test
    void unanimous_anyChallenged_resultsFlagged() {
        final var attestations = List.of(
                attest(AttestationVerdict.SOUND, 0.9),
                attest(AttestationVerdict.CHALLENGED, 0.4));
        final var result = aggregator.aggregate(attestations, Strategy.UNANIMOUS_REQUIRED);
        assertThat(result.get().consensusVerdict()).isEqualTo(AttestationVerdict.FLAGGED);
    }

    // ── FIRST_ATTESTOR ────────────────────────────────────────────────────────

    @Test
    void firstAttestor_usesFirstAttestation_flaggedFirst() {
        final var attestations = List.of(
                attest(AttestationVerdict.FLAGGED, 0.5),
                attest(AttestationVerdict.SOUND, 0.9));
        final var result = aggregator.aggregate(attestations, Strategy.FIRST_ATTESTOR);
        assertThat(result.get().consensusVerdict()).isEqualTo(AttestationVerdict.FLAGGED);
        assertThat(result.get().aggregatedConfidence()).isCloseTo(0.5, within(0.001));
        assertThat(result.get().attestorCount()).isEqualTo(1);
    }

    @Test
    void firstAttestor_usesFirstAttestation_soundFirst() {
        final var attestations = List.of(
                attest(AttestationVerdict.SOUND, 0.8),
                attest(AttestationVerdict.FLAGGED, 0.9));
        final var result = aggregator.aggregate(attestations, Strategy.FIRST_ATTESTOR);
        assertThat(result.get().consensusVerdict()).isEqualTo(AttestationVerdict.SOUND);
        assertThat(result.get().aggregatedConfidence()).isCloseTo(0.8, within(0.001));
    }

    @Test
    void firstAttestor_endorsed_mapsToSound() {
        final var result = aggregator.aggregate(
                List.of(attest(AttestationVerdict.ENDORSED, 0.7)), Strategy.FIRST_ATTESTOR);
        assertThat(result.get().consensusVerdict()).isEqualTo(AttestationVerdict.SOUND);
    }

    @Test
    void firstAttestor_challenged_mapsToFlagged() {
        final var result = aggregator.aggregate(
                List.of(attest(AttestationVerdict.CHALLENGED, 0.6)), Strategy.FIRST_ATTESTOR);
        assertThat(result.get().consensusVerdict()).isEqualTo(AttestationVerdict.FLAGGED);
    }

    // ── UNANIMOUS_REQUIRED FLAGGED confidence is order-independent ───────────

    @Test
    void unanimous_flaggedConfidence_usesMaxNegative_orderIndependent() {
        // Two FLAGGED with different confidences — result must use the higher one regardless of order
        final var attestations1 = List.of(
                attest(AttestationVerdict.SOUND, 0.9),
                attest(AttestationVerdict.FLAGGED, 0.3),
                attest(AttestationVerdict.FLAGGED, 0.8));
        final var attestations2 = List.of(
                attest(AttestationVerdict.FLAGGED, 0.8),
                attest(AttestationVerdict.FLAGGED, 0.3),
                attest(AttestationVerdict.SOUND, 0.9));

        final double confidence1 = aggregator.aggregate(attestations1, Strategy.UNANIMOUS_REQUIRED)
                .map(AggregatedAttestation::aggregatedConfidence).orElseThrow();
        final double confidence2 = aggregator.aggregate(attestations2, Strategy.UNANIMOUS_REQUIRED)
                .map(AggregatedAttestation::aggregatedConfidence).orElseThrow();

        assertThat(confidence1).isCloseTo(0.8, within(0.001));
        assertThat(confidence2).isCloseTo(0.8, within(0.001));
    }

    // ── attestor count ────────────────────────────────────────────────────────

    @Test
    void attestorCount_reflectsTotalInputSize() {
        final var attestations = List.of(
                attest(AttestationVerdict.SOUND, 0.9),
                attest(AttestationVerdict.SOUND, 0.8),
                attest(AttestationVerdict.FLAGGED, 0.2));
        final AggregatedAttestation result = aggregator.aggregate(attestations, Strategy.UNANIMOUS_REQUIRED).orElseThrow();
        assertThat(result.attestorCount()).isEqualTo(3);
    }

    // ── fixture ───────────────────────────────────────────────────────────────

    private LedgerAttestation attest(final AttestationVerdict verdict, final double confidence) {
        final LedgerAttestation a = new LedgerAttestation();
        a.id = UUID.randomUUID();
        a.ledgerEntryId = UUID.randomUUID();
        a.subjectId = UUID.randomUUID();
        a.attestorId = "test-attestor";
        a.verdict = verdict;
        a.confidence = confidence;
        a.capabilityTag = "*";
        a.occurredAt = Instant.now();
        return a;
    }
}
