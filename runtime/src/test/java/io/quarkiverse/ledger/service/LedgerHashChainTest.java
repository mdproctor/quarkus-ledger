package io.quarkiverse.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.model.supplement.ComplianceSupplement;
import io.quarkiverse.ledger.runtime.service.LedgerHashChain;

/**
 * Pure JUnit 5 unit tests for {@link LedgerHashChain} — no Quarkus runtime, no CDI.
 *
 * <p>
 * Uses a package-private concrete subclass {@code TestLedgerEntry} since {@link LedgerEntry}
 * is abstract. All fields exercised in the canonical form are base-class fields:
 * {@code subjectId|seqNum|entryType|actorId|actorRole|occurredAt}.
 */
class LedgerHashChainTest {

    // -------------------------------------------------------------------------
    // Concrete subclass for unit testing (LedgerEntry is abstract)
    // -------------------------------------------------------------------------

    /** Minimal concrete subclass — adds nothing beyond the abstract base. */
    private static class TestLedgerEntry extends LedgerEntry {
    }

    // -------------------------------------------------------------------------
    // Fixture helper
    // -------------------------------------------------------------------------

    private TestLedgerEntry entry(final UUID subjectId, final int seq) {
        final TestLedgerEntry e = new TestLedgerEntry();
        e.subjectId = subjectId;
        e.sequenceNumber = seq;
        e.entryType = LedgerEntryType.EVENT;
        e.actorId = "system";
        e.actorRole = "Initiator";
        // planRef removed — now lives in ComplianceSupplement
        e.occurredAt = Instant.parse("2026-04-14T10:00:00Z");
        return e;
    }

    // -------------------------------------------------------------------------
    // compute — basic contract
    // -------------------------------------------------------------------------

    @Test
    void compute_returnsNonNullDigest() {
        final TestLedgerEntry e = entry(UUID.randomUUID(), 1);

        assertThat(LedgerHashChain.compute(null, e)).isNotNull();
    }

    @Test
    void compute_returns64CharHexString() {
        final TestLedgerEntry e = entry(UUID.randomUUID(), 1);

        final String digest = LedgerHashChain.compute(null, e);

        assertThat(digest).hasSize(64);
        assertThat(digest).matches("[0-9a-f]{64}");
    }

    @Test
    void compute_withNullPreviousHash_isDeterministic() {
        final UUID id = UUID.randomUUID();
        final TestLedgerEntry e = entry(id, 1);

        final String first = LedgerHashChain.compute(null, e);
        final String second = LedgerHashChain.compute(null, e);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void compute_differentPreviousHash_producesDifferentDigest() {
        final TestLedgerEntry e = entry(UUID.randomUUID(), 2);

        final String withNull = LedgerHashChain.compute(null, e);
        final String withPrev = LedgerHashChain.compute("abc123", e);

        assertThat(withNull).isNotEqualTo(withPrev);
    }

    // -------------------------------------------------------------------------
    // compute — canonical fields mutated
    // -------------------------------------------------------------------------

    @Test
    void compute_mutatingSubjectId_changesDifferentDigest() {
        final TestLedgerEntry e = entry(UUID.randomUUID(), 1);
        final String before = LedgerHashChain.compute(null, e);

        e.subjectId = UUID.randomUUID();
        final String after = LedgerHashChain.compute(null, e);

        assertThat(before).isNotEqualTo(after);
    }

    @Test
    void compute_mutatingActorId_changesDifferentDigest() {
        final TestLedgerEntry e = entry(UUID.randomUUID(), 1);
        final String before = LedgerHashChain.compute(null, e);

        e.actorId = "mutated-actor";
        final String after = LedgerHashChain.compute(null, e);

        assertThat(before).isNotEqualTo(after);
    }

    @Test
    void compute_mutatingActorRole_changesDifferentDigest() {
        final TestLedgerEntry e = entry(UUID.randomUUID(), 1);
        final String before = LedgerHashChain.compute(null, e);

        e.actorRole = "mutated-role";
        final String after = LedgerHashChain.compute(null, e);

        assertThat(before).isNotEqualTo(after);
    }

    @Test
    void compute_mutatingSequenceNumber_changesDifferentDigest() {
        final TestLedgerEntry e = entry(UUID.randomUUID(), 1);
        final String before = LedgerHashChain.compute(null, e);

        e.sequenceNumber = 99;
        final String after = LedgerHashChain.compute(null, e);

        assertThat(before).isNotEqualTo(after);
    }

    @Test
    void compute_mutatingOccurredAt_changesDifferentDigest() {
        final TestLedgerEntry e = entry(UUID.randomUUID(), 1);
        final String before = LedgerHashChain.compute(null, e);

        e.occurredAt = Instant.parse("2025-01-01T00:00:00Z");
        final String after = LedgerHashChain.compute(null, e);

        assertThat(before).isNotEqualTo(after);
    }

    // -------------------------------------------------------------------------
    // verify — empty and single-entry chains
    // -------------------------------------------------------------------------

    @Test
    void verify_emptyList_returnsTrue() {
        assertThat(LedgerHashChain.verify(Collections.emptyList())).isTrue();
    }

    @Test
    void verify_singleEntry_withCorrectDigest_returnsTrue() {
        final TestLedgerEntry e = entry(UUID.randomUUID(), 1);
        e.previousHash = null;
        e.digest = LedgerHashChain.compute(null, e);

        assertThat(LedgerHashChain.verify(List.of(e))).isTrue();
    }

    @Test
    void verify_singleEntry_withWrongDigest_returnsFalse() {
        final TestLedgerEntry e = entry(UUID.randomUUID(), 1);
        e.previousHash = null;
        e.digest = "deadbeef00000000000000000000000000000000000000000000000000000000";

        assertThat(LedgerHashChain.verify(List.of(e))).isFalse();
    }

    // -------------------------------------------------------------------------
    // verify — two-entry chains
    // -------------------------------------------------------------------------

    @Test
    void verify_twoEntries_validChain_returnsTrue() {
        final UUID id = UUID.randomUUID();

        final TestLedgerEntry e1 = entry(id, 1);
        e1.previousHash = null;
        e1.digest = LedgerHashChain.compute(null, e1);

        final TestLedgerEntry e2 = entry(id, 2);
        e2.actorId = "alice";
        e2.actorRole = "Claimant";
        e2.previousHash = e1.digest;
        e2.digest = LedgerHashChain.compute(e1.digest, e2);

        assertThat(LedgerHashChain.verify(List.of(e1, e2))).isTrue();
    }

    @Test
    void verify_twoEntries_tamperedFirstEntry_returnsFalse() {
        final UUID id = UUID.randomUUID();

        final TestLedgerEntry e1 = entry(id, 1);
        e1.previousHash = null;
        e1.digest = LedgerHashChain.compute(null, e1);

        final TestLedgerEntry e2 = entry(id, 2);
        e2.previousHash = e1.digest;
        e2.digest = LedgerHashChain.compute(e1.digest, e2);

        // Tamper: mutate e1 actorId without recomputing digest
        e1.actorId = "mallory";

        assertThat(LedgerHashChain.verify(List.of(e1, e2))).isFalse();
    }

    @Test
    void verify_twoEntries_tamperedSecondEntry_returnsFalse() {
        final UUID id = UUID.randomUUID();

        final TestLedgerEntry e1 = entry(id, 1);
        e1.previousHash = null;
        e1.digest = LedgerHashChain.compute(null, e1);

        final TestLedgerEntry e2 = entry(id, 2);
        e2.actorId = "alice";
        e2.previousHash = e1.digest;
        e2.digest = LedgerHashChain.compute(e1.digest, e2);

        // Tamper: mutate e2 actorId without recomputing digest
        e2.actorId = "mallory";

        assertThat(LedgerHashChain.verify(List.of(e1, e2))).isFalse();
    }

    @Test
    void verify_fourEntries_validChain_returnsTrue() {
        final UUID id = UUID.randomUUID();
        String prevHash = null;
        final List<LedgerEntry> entries = new java.util.ArrayList<>();

        for (int seq = 1; seq <= 4; seq++) {
            final TestLedgerEntry e = entry(id, seq);
            e.actorId = "actor-" + seq;
            e.previousHash = prevHash;
            e.digest = LedgerHashChain.compute(prevHash, e);
            entries.add(e);
            prevHash = e.digest;
        }

        assertThat(LedgerHashChain.verify(entries)).isTrue();
    }

    // -------------------------------------------------------------------------
    // genesisHash sentinel
    // -------------------------------------------------------------------------

    @Test
    void genesisHash_returnsGENESIS() {
        assertThat(LedgerHashChain.genesisHash()).isEqualTo("GENESIS");
    }

    // ── canonical form — supplement fields excluded ───────────────────────────

    @Test
    void compute_supplementFieldsDoNotAffectDigest() {
        // Canonical form covers only core LedgerEntry fields.
        // Supplement data is not tamper-evidence — it is enrichment.
        // An entry with a fully-populated ComplianceSupplement must produce
        // the same digest as an identical bare entry with no supplements.
        final UUID id = UUID.randomUUID();
        final TestLedgerEntry bare = entry(id, 1);

        final TestLedgerEntry withSupplement = entry(id, 1);
        final ComplianceSupplement cs = new ComplianceSupplement();
        cs.algorithmRef = "gpt-4o";
        cs.confidenceScore = 0.99;
        cs.contestationUri = "https://example.com/challenge";
        cs.humanOverrideAvailable = true;
        cs.planRef = "policy-v9";
        cs.rationale = "Threshold exceeded";
        withSupplement.attach(cs);

        assertThat(LedgerHashChain.compute(null, bare))
                .isEqualTo(LedgerHashChain.compute(null, withSupplement));
    }
}
