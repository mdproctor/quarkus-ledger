package io.quarkiverse.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.Test;

import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.model.AttestationVerdict;
import io.quarkiverse.ledger.runtime.model.LedgerAttestation;
import io.quarkiverse.ledger.runtime.model.LedgerEntryArchiveRecord;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.repository.LedgerEntryRepository;
import io.quarkiverse.ledger.runtime.service.LedgerRetentionJob;
import io.quarkiverse.ledger.service.supplement.TestEntry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(LedgerRetentionJobIT.RetentionProfile.class)
class LedgerRetentionJobIT {

    public static class RetentionProfile implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "retention-test";
        }
    }

    @Inject
    LedgerRetentionJob retentionJob;

    @Inject
    LedgerEntryRepository repo;

    @Inject
    EntityManager em;

    @Test
    @Transactional
    void oldEntries_archivedAndDeleted() {
        final UUID subjectId = UUID.randomUUID();
        chainedEntry(subjectId, 1, now().minus(60, ChronoUnit.DAYS));
        chainedEntry(subjectId, 2, now().minus(45, ChronoUnit.DAYS));

        retentionJob.runRetention();

        assertThat(repo.findBySubjectId(subjectId)).isEmpty();
        assertThat(archiveCountForSubject(subjectId)).isEqualTo(2);
    }

    @Test
    @Transactional
    void newEntries_notDeleted() {
        final UUID subjectId = UUID.randomUUID();
        chainedEntry(subjectId, 1, now().minus(5, ChronoUnit.DAYS));

        retentionJob.runRetention();

        assertThat(repo.findBySubjectId(subjectId)).hasSize(1);
        assertThat(archiveCountForSubject(subjectId)).isZero();
    }

    @Test
    @Transactional
    void archiveRecord_containsEntryJson() {
        final UUID subjectId = UUID.randomUUID();
        chainedEntry(subjectId, 1, now().minus(40, ChronoUnit.DAYS));

        retentionJob.runRetention();

        final List<LedgerEntryArchiveRecord> records = em.createQuery(
                "SELECT r FROM LedgerEntryArchiveRecord r WHERE r.subjectId = :subjectId",
                LedgerEntryArchiveRecord.class)
                .setParameter("subjectId", subjectId)
                .getResultList();
        assertThat(records).hasSize(1);
        final LedgerEntryArchiveRecord record = records.get(0);
        assertThat(record).isNotNull();
        assertThat(record.entryJson).contains("\"actorId\":\"retention-actor\"");
        assertThat(record.entryJson).contains("\"sequenceNumber\":1");
        assertThat(record.archivedAt).isNotNull();
    }

    @Test
    @Transactional
    void attestations_deletedBeforeEntry_noFkViolation() {
        final UUID subjectId = UUID.randomUUID();
        final TestEntry e = chainedEntry(subjectId, 1, now().minus(40, ChronoUnit.DAYS));
        seedAttestation(e.id, subjectId, AttestationVerdict.SOUND);

        // FK ordering wrong → would throw constraint violation
        retentionJob.runRetention();

        assertThat(repo.findBySubjectId(subjectId)).isEmpty();
        final long attestCount = (Long) em.createQuery(
                "SELECT COUNT(a) FROM LedgerAttestation a WHERE a.ledgerEntryId = :entryId")
                .setParameter("entryId", e.id)
                .getSingleResult();
        assertThat(attestCount).isZero();
    }

    @Test
    @Transactional
    void mixedAges_subjectNotArchived() {
        final UUID subjectId = UUID.randomUUID();
        chainedEntry(subjectId, 1, now().minus(60, ChronoUnit.DAYS));
        chainedEntry(subjectId, 2, now().minus(5, ChronoUnit.DAYS));

        retentionJob.runRetention();

        assertThat(repo.findBySubjectId(subjectId)).hasSize(2);
        assertThat(archiveCountForSubject(subjectId)).isZero();
    }

    @Test
    @Transactional
    void brokenChain_subjectSkipped() {
        final UUID subjectId = UUID.randomUUID();
        final TestEntry e = chainedEntry(subjectId, 1, now().minus(50, ChronoUnit.DAYS));
        // Tamper: corrupt the digest so chain verification fails
        e.digest = "000000000000000000000000000000000000000000000000000000000000dead";
        repo.save(e);

        retentionJob.runRetention();

        assertThat(repo.findBySubjectId(subjectId)).hasSize(1);
        assertThat(archiveCountForSubject(subjectId)).isZero();
    }

    private long archiveCountForSubject(final UUID subjectId) {
        return (Long) em.createQuery(
                "SELECT COUNT(r) FROM LedgerEntryArchiveRecord r WHERE r.subjectId = :subjectId")
                .setParameter("subjectId", subjectId)
                .getSingleResult();
    }

    private TestEntry chainedEntry(final UUID subjectId, final int seq,
            final Instant occurredAt) {
        final TestEntry e = new TestEntry();
        e.subjectId = subjectId;
        e.sequenceNumber = seq;
        e.entryType = LedgerEntryType.EVENT;
        e.actorId = "retention-actor";
        e.actorType = ActorType.AGENT;
        e.actorRole = "Classifier";
        e.occurredAt = occurredAt;
        return (TestEntry) repo.save(e);
    }

    private void seedAttestation(final UUID entryId, final UUID subjectId,
            final AttestationVerdict verdict) {
        final LedgerAttestation att = new LedgerAttestation();
        att.id = UUID.randomUUID();
        att.ledgerEntryId = entryId;
        att.subjectId = subjectId;
        att.attestorId = "compliance-bot";
        att.attestorType = ActorType.AGENT;
        att.verdict = verdict;
        att.confidence = 0.9;
        att.occurredAt = Instant.now();
        em.persist(att);
    }

    private Instant now() {
        return Instant.now();
    }
}
