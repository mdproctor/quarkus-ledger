package io.casehub.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.CapabilityTag;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.runtime.model.LedgerAttestation;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;
import io.casehub.ledger.service.supplement.TestEntry;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for dimension-tagged attestation persistence (#62).
 *
 * <p>Covers: happy path, correctness/isolation, and backward compatibility.
 */
@QuarkusTest
class LedgerAttestationDimensionIT {

    @Inject
    LedgerEntryRepository repo;

    @Inject
    EntityManager em;

    // ── Happy path: trustDimension and dimensionScore stored and retrieved ────

    @Test
    @Transactional
    void dimensionAttestation_storedAndRetrieved() {
        final TestEntry entry = savedEntry();
        final LedgerAttestation att = dimensionAttestation(entry.id, entry.subjectId,
                "review-thoroughness", 0.75);
        em.persist(att);
        em.flush();
        em.clear();

        final List<LedgerAttestation> results = repo.findAttestationsByEntryId(entry.id);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).trustDimension).isEqualTo("review-thoroughness");
        assertThat(results.get(0).dimensionScore).isEqualTo(0.75);
    }

    // ── Happy path: dimensionScore = 0.0 is stored (not treated as null) ─────

    @Test
    @Transactional
    void dimensionAttestation_zeroScore_stored() {
        final TestEntry entry = savedEntry();
        final LedgerAttestation att = dimensionAttestation(entry.id, entry.subjectId,
                "false-positive-rate", 0.0);
        em.persist(att);
        em.flush();
        em.clear();

        final List<LedgerAttestation> results = repo.findAttestationsByEntryId(entry.id);

        assertThat(results.get(0).dimensionScore).isEqualTo(0.0);
    }

    // ── Correctness: ordinary attestation has null trustDimension/dimensionScore

    @Test
    @Transactional
    void ordinaryAttestation_nullDimensionFields() {
        final TestEntry entry = savedEntry();
        final LedgerAttestation att = new LedgerAttestation();
        att.id = UUID.randomUUID();
        att.ledgerEntryId = entry.id;
        att.subjectId = entry.subjectId;
        att.attestorId = "peer";
        att.attestorType = ActorType.AGENT;
        att.verdict = AttestationVerdict.SOUND;
        att.confidence = 1.0;
        att.capabilityTag = CapabilityTag.GLOBAL;
        att.occurredAt = Instant.now();
        em.persist(att);
        em.flush();
        em.clear();

        final List<LedgerAttestation> results = repo.findAttestationsByEntryId(entry.id);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).trustDimension).isNull();
        assertThat(results.get(0).dimensionScore).isNull();
    }

    // ── Correctness: dimension and ordinary attestations coexist on same entry

    @Test
    @Transactional
    void dimensionAndOrdinaryAttestations_coexistOnSameEntry() {
        final TestEntry entry = savedEntry();

        final LedgerAttestation ordinary = new LedgerAttestation();
        ordinary.id = UUID.randomUUID();
        ordinary.ledgerEntryId = entry.id;
        ordinary.subjectId = entry.subjectId;
        ordinary.attestorId = "peer";
        ordinary.attestorType = ActorType.AGENT;
        ordinary.verdict = AttestationVerdict.SOUND;
        ordinary.confidence = 1.0;
        ordinary.capabilityTag = CapabilityTag.GLOBAL;
        ordinary.occurredAt = Instant.now();
        em.persist(ordinary);

        em.persist(dimensionAttestation(entry.id, entry.subjectId, "thoroughness", 0.8));
        em.flush();
        em.clear();

        final List<LedgerAttestation> results = repo.findAttestationsByEntryId(entry.id);

        assertThat(results).hasSize(2);
        final long withDimension = results.stream().filter(a -> a.trustDimension != null).count();
        final long withoutDimension = results.stream().filter(a -> a.trustDimension == null).count();
        assertThat(withDimension).isEqualTo(1);
        assertThat(withoutDimension).isEqualTo(1);
    }

    // ── Robustness: multiple dimension attestations on same entry ─────────────

    @Test
    @Transactional
    void multipleDimensionAttestations_sameEntry_storedSeparately() {
        final TestEntry entry = savedEntry();
        em.persist(dimensionAttestation(entry.id, entry.subjectId, "thoroughness", 0.9));
        em.persist(dimensionAttestation(entry.id, entry.subjectId, "false-positive-rate", 0.1));
        em.flush();
        em.clear();

        final List<LedgerAttestation> results = repo.findAttestationsByEntryId(entry.id);

        assertThat(results).hasSize(2);
        assertThat(results).extracting(a -> a.trustDimension)
                .containsExactlyInAnyOrder("thoroughness", "false-positive-rate");
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private TestEntry savedEntry() {
        final TestEntry entry = new TestEntry();
        entry.subjectId = UUID.randomUUID();
        entry.sequenceNumber = 1;
        entry.entryType = LedgerEntryType.EVENT;
        entry.actorId = "reviewer-" + UUID.randomUUID();
        entry.actorType = ActorType.AGENT;
        entry.occurredAt = Instant.now().minus(1, ChronoUnit.HOURS);
        repo.save(entry);
        return entry;
    }

    private LedgerAttestation dimensionAttestation(final UUID entryId, final UUID subjectId,
            final String dimension, final double score) {
        final LedgerAttestation att = new LedgerAttestation();
        att.id = UUID.randomUUID();
        att.ledgerEntryId = entryId;
        att.subjectId = subjectId;
        att.attestorId = "dimension-peer";
        att.attestorType = ActorType.AGENT;
        att.verdict = AttestationVerdict.SOUND;
        att.confidence = 1.0;
        att.capabilityTag = CapabilityTag.GLOBAL;
        att.trustDimension = dimension;
        att.dimensionScore = score;
        att.occurredAt = Instant.now();
        return att;
    }
}
