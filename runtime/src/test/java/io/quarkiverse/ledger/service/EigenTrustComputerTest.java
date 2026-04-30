package io.casehub.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.runtime.model.LedgerAttestation;
import io.casehub.ledger.runtime.service.EigenTrustComputer;

/**
 * Pure JUnit 5 unit tests for {@link EigenTrustComputer} — no Quarkus runtime, no CDI.
 *
 * <p>
 * The key property under test is transitivity: trust propagates through attestation chains
 * so that an actor attested only by peers of trusted peers still accumulates global trust.
 */
class EigenTrustComputerTest {

    private final EigenTrustComputer computer = new EigenTrustComputer(0.15);

    // ── Fixture helpers ───────────────────────────────────────────────────────

    private static LedgerAttestation att(
            final String attestorId, final UUID ledgerEntryId, final AttestationVerdict verdict) {
        final LedgerAttestation a = new LedgerAttestation();
        a.id = UUID.randomUUID();
        a.attestorId = attestorId;
        a.ledgerEntryId = ledgerEntryId;
        a.attestorType = ActorType.AGENT;
        a.verdict = verdict;
        a.confidence = 0.9;
        return a;
    }

    // ── Empty input ───────────────────────────────────────────────────────────

    @Test
    void emptyAttestations_returnsEmptyMap() {
        final Map<String, Double> result = computer.compute(List.of(), Map.of(), Set.of());
        assertThat(result).isEmpty();
    }

    // ── Scores are always in valid range ──────────────────────────────────────

    @Test
    void scores_alwaysWithinZeroToOne() {
        final UUID e1 = UUID.randomUUID();
        final UUID e2 = UUID.randomUUID();
        final UUID e3 = UUID.randomUUID();
        final List<LedgerAttestation> attestations = List.of(
                att("actor-A", e1, AttestationVerdict.SOUND),
                att("actor-B", e2, AttestationVerdict.FLAGGED),
                att("actor-C", e3, AttestationVerdict.ENDORSED),
                att("actor-A", e3, AttestationVerdict.CHALLENGED));
        final Map<UUID, String> index = Map.of(e1, "actor-B", e2, "actor-C", e3, "actor-A");

        final Map<String, Double> result = computer.compute(attestations, index, Set.of("actor-A"));

        result.values().forEach(score -> assertThat(score).isBetween(0.0, 1.0));
    }

    // ── Mutual positive attestation ───────────────────────────────────────────

    @Test
    void mutualPositiveAttestation_producesSymmetricScores() {
        // A attests B positively; B attests A positively — symmetric graph, symmetric scores.
        final UUID entryByA = UUID.randomUUID();
        final UUID entryByB = UUID.randomUUID();
        final List<LedgerAttestation> attestations = List.of(
                att("actor-A", entryByB, AttestationVerdict.SOUND),
                att("actor-B", entryByA, AttestationVerdict.SOUND));
        final Map<UUID, String> index = Map.of(entryByA, "actor-A", entryByB, "actor-B");

        final Map<String, Double> result = computer.compute(attestations, index, Set.of());

        assertThat(result.get("actor-A")).isCloseTo(result.get("actor-B"), within(0.01));
    }

    // ── Pre-trusted actor anchoring ───────────────────────────────────────────

    @Test
    void preTrustedActor_receivesHigherShareThanUnknown() {
        // A is pre-trusted. A attests B positively. C is completely unknown.
        // Expected ordering: A ≥ B > C (A is anchored by pre-trust; C has no connections).
        final UUID entryByB = UUID.randomUUID();
        final List<LedgerAttestation> attestations = List.of(
                att("actor-A", entryByB, AttestationVerdict.SOUND));
        final Map<UUID, String> index = Map.of(entryByB, "actor-B");
        // Include C by adding it as an attestor with no positive history
        final UUID entryByC = UUID.randomUUID();
        final List<LedgerAttestation> withC = List.of(
                att("actor-A", entryByB, AttestationVerdict.SOUND),
                att("actor-C", entryByB, AttestationVerdict.FLAGGED)); // C flags B — only negative
        final Map<UUID, String> indexWithC = Map.of(entryByB, "actor-B");

        final Map<String, Double> result = computer.compute(withC, indexWithC, Set.of("actor-A"));

        // A is pre-trusted: it should have a higher score than C (which only has negatives)
        assertThat(result.get("actor-A")).isGreaterThan(result.get("actor-C"));
    }

    // ── Transitivity: the core EigenTrust property ───────────────────────────

    @Test
    void transitiveChain_propagatesTrustBeyondDirectAttestation() {
        // 4-actor chain: A is pre-trusted; A attests B positively; B attests C positively.
        // D is completely unknown (no attestation connections).
        //
        // EigenTrust should propagate A's trust through B to C:
        //   B > D (B gets direct trust from pre-trusted A)
        //   C > D (C gets transitive trust via B — this is the transitivity assertion)
        final UUID entryByB = UUID.randomUUID();
        final UUID entryByC = UUID.randomUUID();
        final UUID entryByD = UUID.randomUUID();

        final List<LedgerAttestation> attestations = List.of(
                att("actor-A", entryByB, AttestationVerdict.SOUND), // A → B
                att("actor-B", entryByC, AttestationVerdict.SOUND), // B → C (transitive)
                att("actor-D", entryByD, AttestationVerdict.FLAGGED)); // D self-negative (to appear in actors)

        final Map<UUID, String> index = Map.of(
                entryByB, "actor-B",
                entryByC, "actor-C",
                entryByD, "actor-D");

        final Map<String, Double> result = computer.compute(
                attestations, index, Set.of("actor-A"));

        assertThat(result).containsKeys("actor-A", "actor-B", "actor-C", "actor-D");

        // Direct trust: B is attested by pre-trusted A
        assertThat(result.get("actor-B")).isGreaterThan(result.get("actor-D"));

        // Transitivity: C receives trust via A→B→C chain; D has no connections
        assertThat(result.get("actor-C")).isGreaterThan(result.get("actor-D"));
    }

    @Test
    void transitiveChain_directTrustExceedsTransitive() {
        // In the same A→B→C chain, B (direct) should rank higher than C (transitive).
        // This verifies the signal degrades over hops, as expected.
        final UUID entryByB = UUID.randomUUID();
        final UUID entryByC = UUID.randomUUID();

        final List<LedgerAttestation> attestations = List.of(
                att("actor-A", entryByB, AttestationVerdict.SOUND),
                att("actor-B", entryByC, AttestationVerdict.SOUND));
        final Map<UUID, String> index = Map.of(entryByB, "actor-B", entryByC, "actor-C");

        // Run enough iterations to reach a stable ordering
        final EigenTrustComputer stable = new EigenTrustComputer(0.3); // higher alpha = faster convergence
        final Map<String, Double> result = stable.compute(attestations, index, Set.of("actor-A"));

        // B is one hop from the pre-trusted anchor; C is two hops — B should rank higher
        assertThat(result.get("actor-B")).isGreaterThan(result.get("actor-C"));
    }

    // ── Negative attestations reduce trust ───────────────────────────────────

    @Test
    void negativeAttestations_reduceTrustRelativeToPeer() {
        // A (pre-trusted) flags B; A endorses C.
        // C should accumulate more trust than B.
        final UUID entryByB = UUID.randomUUID();
        final UUID entryByC = UUID.randomUUID();

        final List<LedgerAttestation> attestations = List.of(
                att("actor-A", entryByB, AttestationVerdict.FLAGGED), // A distrusts B
                att("actor-A", entryByC, AttestationVerdict.ENDORSED)); // A trusts C
        final Map<UUID, String> index = Map.of(entryByB, "actor-B", entryByC, "actor-C");

        final Map<String, Double> result = computer.compute(attestations, index, Set.of("actor-A"));

        assertThat(result.get("actor-C")).isGreaterThan(result.get("actor-B"));
    }

    // ── Alpha sensitivity ─────────────────────────────────────────────────────

    @Test
    void higherAlpha_anchorsMoreStronglyToPreTrusted() {
        // With high alpha, the pre-trusted distribution dominates.
        // Pre-trusted actor A should have a larger share with alpha=0.8 vs alpha=0.15.
        final UUID entryByB = UUID.randomUUID();
        final List<LedgerAttestation> attestations = List.of(
                att("actor-A", entryByB, AttestationVerdict.SOUND));
        final Map<UUID, String> index = Map.of(entryByB, "actor-B");

        final EigenTrustComputer lowAlpha = new EigenTrustComputer(0.15);
        final EigenTrustComputer highAlpha = new EigenTrustComputer(0.8);

        final Map<String, Double> lowResult = lowAlpha.compute(attestations, index, Set.of("actor-A"));
        final Map<String, Double> highResult = highAlpha.compute(attestations, index, Set.of("actor-A"));

        // Higher alpha keeps more of the trust anchored at A
        assertThat(highResult.get("actor-A")).isGreaterThan(lowResult.get("actor-A"));
    }
}
