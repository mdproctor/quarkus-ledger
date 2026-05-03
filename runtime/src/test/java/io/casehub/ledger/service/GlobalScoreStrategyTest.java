package io.casehub.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.CapabilityTag;
import io.casehub.ledger.runtime.model.LedgerAttestation;
import io.casehub.ledger.runtime.service.AllAttestationsGlobalStrategy;
import io.casehub.ledger.runtime.service.ExplicitGlobalAttestationsStrategy;
import io.casehub.ledger.runtime.service.FrequencyWeightedGlobalStrategy;
import io.casehub.ledger.runtime.service.TrustScoreComputer;

/**
 * Pure unit tests for all three {@link io.casehub.ledger.runtime.service.GlobalScoreStrategy} implementations.
 * No Quarkus runtime, no CDI.
 */
class GlobalScoreStrategyTest {

    // ── AllAttestationsGlobalStrategy ─────────────────────────────────────────

    @Test
    void allAttestations_returnsFullList() {
        final var strategy = new AllAttestationsGlobalStrategy();
        final List<LedgerAttestation> all = List.of(attestation("*"), attestation("security-review"));

        assertThat(strategy.selectAttestations(all)).isSameAs(all);
    }

    @Test
    void allAttestations_derive_returnsEmpty() {
        final var strategy = new AllAttestationsGlobalStrategy();
        final Map<String, TrustScoreComputer.ActorScore> caps = Map.of(
                "security-review", score(0.8));

        assertThat(strategy.derive(caps, List.of())).isEmpty();
    }

    @Test
    void allAttestations_emptyInput_returnsEmpty() {
        final var strategy = new AllAttestationsGlobalStrategy();

        assertThat(strategy.selectAttestations(List.of())).isEmpty();
    }

    // ── ExplicitGlobalAttestationsStrategy ───────────────────────────────────

    @Test
    void explicitGlobal_filtersToStarOnly() {
        final var strategy = new ExplicitGlobalAttestationsStrategy();
        final LedgerAttestation global = attestation(CapabilityTag.GLOBAL);
        final LedgerAttestation capability = attestation("security-review");

        final List<LedgerAttestation> result = strategy.selectAttestations(List.of(global, capability));

        assertThat(result).containsExactly(global);
    }

    @Test
    void explicitGlobal_emptyWhenNoGlobalAttestations() {
        final var strategy = new ExplicitGlobalAttestationsStrategy();
        final List<LedgerAttestation> capOnly = List.of(
                attestation("security-review"),
                attestation("style-review"));

        assertThat(strategy.selectAttestations(capOnly)).isEmpty();
    }

    @Test
    void explicitGlobal_allReturnedWhenAllAreGlobal() {
        final var strategy = new ExplicitGlobalAttestationsStrategy();
        final List<LedgerAttestation> all = List.of(attestation("*"), attestation("*"));

        assertThat(strategy.selectAttestations(all)).hasSize(2);
    }

    @Test
    void explicitGlobal_derive_returnsEmpty() {
        final var strategy = new ExplicitGlobalAttestationsStrategy();

        assertThat(strategy.derive(Map.of("security-review", score(0.9)), List.of())).isEmpty();
    }

    // ── FrequencyWeightedGlobalStrategy ──────────────────────────────────────

    @Test
    void frequencyWeighted_selectAttestations_alwaysEmpty() {
        final var strategy = new FrequencyWeightedGlobalStrategy();
        final List<LedgerAttestation> all = List.of(attestation("*"), attestation("security-review"));

        assertThat(strategy.selectAttestations(all)).isEmpty();
    }

    @Test
    void frequencyWeighted_derive_returnsEmptyWhenNoCapabilities() {
        final var strategy = new FrequencyWeightedGlobalStrategy();

        assertThat(strategy.derive(Map.of(), List.of())).isEmpty();
    }

    @Test
    void frequencyWeighted_derive_equalWeightsWhenEqualCounts() {
        final var strategy = new FrequencyWeightedGlobalStrategy();
        final List<LedgerAttestation> all = List.of(
                attestation("security-review"),
                attestation("style-review"));
        final Map<String, TrustScoreComputer.ActorScore> caps = Map.of(
                "security-review", score(0.9),
                "style-review", score(0.5));

        final var result = strategy.derive(caps, all);

        assertThat(result).isPresent();
        assertThat(result.get().trustScore()).isCloseTo(0.7, within(0.01));
    }

    @Test
    void frequencyWeighted_derive_higherWeightForMoreAttestedCapability() {
        final var strategy = new FrequencyWeightedGlobalStrategy();
        final List<LedgerAttestation> all = List.of(
                attestation("security-review"),
                attestation("security-review"),
                attestation("security-review"),
                attestation("style-review"));
        final Map<String, TrustScoreComputer.ActorScore> caps = Map.of(
                "security-review", score(0.8),
                "style-review", score(0.2));

        final var result = strategy.derive(caps, all);

        assertThat(result).isPresent();
        assertThat(result.get().trustScore()).isCloseTo(0.65, within(0.01));
    }

    @Test
    void frequencyWeighted_derive_ignoresGlobalAttestationsInWeighting() {
        final var strategy = new FrequencyWeightedGlobalStrategy();
        final List<LedgerAttestation> all = List.of(
                attestation(CapabilityTag.GLOBAL),
                attestation(CapabilityTag.GLOBAL),
                attestation("security-review"));
        final Map<String, TrustScoreComputer.ActorScore> caps = Map.of(
                "security-review", score(0.8));

        final var result = strategy.derive(caps, all);

        assertThat(result).isPresent();
        assertThat(result.get().trustScore()).isCloseTo(0.8, within(0.01));
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static LedgerAttestation attestation(final String capabilityTag) {
        final LedgerAttestation a = new LedgerAttestation();
        a.id = UUID.randomUUID();
        a.ledgerEntryId = UUID.randomUUID();
        a.subjectId = UUID.randomUUID();
        a.attestorId = "peer";
        a.attestorType = ActorType.AGENT;
        a.verdict = AttestationVerdict.SOUND;
        a.confidence = 1.0;
        a.capabilityTag = capabilityTag;
        a.occurredAt = Instant.now();
        return a;
    }

    private static TrustScoreComputer.ActorScore score(final double trustScore) {
        return new TrustScoreComputer.ActorScore(trustScore, 2.0, 1.0, 1, 0, 1, 0);
    }
}
