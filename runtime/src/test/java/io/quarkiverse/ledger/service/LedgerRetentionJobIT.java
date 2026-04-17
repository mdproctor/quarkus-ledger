package io.quarkiverse.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.Test;

import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.model.AttestationVerdict;
import io.quarkiverse.ledger.runtime.model.LedgerAttestation;
import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkiverse.ledger.runtime.model.LedgerEntryArchiveRecord;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.service.LedgerHashChain;
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

    @Test
    @Transactional
    void oldEntries_archivedAndDeleted() {
        final UUID subjectId = UUID.randomUUID();
        final TestEntry first = chainedEntry(subjectId, 1, now().minus(60, ChronoUnit.DAYS), null);
        chainedEntry(subjectId, 2, now().minus(45, ChronoUnit.DAYS), first.digest);

        retentionJob.runRetention();

        assertThat(LedgerEntry.find("subjectId", subjectId).count()).isZero();
        assertThat(LedgerEntryArchiveRecord.find("subjectId", subjectId).count()).isEqualTo(2);
    }

    @Test
    @Transactional
    void newEntries_notDeleted() {
        final UUID subjectId = UUID.randomUUID();
        chainedEntry(subjectId, 1, now().minus(5, ChronoUnit.DAYS), null);

        retentionJob.runRetention();

        assertThat(LedgerEntry.find("subjectId", subjectId).count()).isEqualTo(1);
        assertThat(LedgerEntryArchiveRecord.find("subjectId", subjectId).count()).isZero();
    }

    @Test
    @Transactional
    void archiveRecord_containsEntryJson() {
        final UUID subjectId = UUID.randomUUID();
        chainedEntry(subjectId, 1, now().minus(40, ChronoUnit.DAYS), null);

        retentionJob.runRetention();

        final LedgerEntryArchiveRecord record = LedgerEntryArchiveRecord
                .<LedgerEntryArchiveRecord> find("subjectId", subjectId).firstResult();
        assertThat(record).isNotNull();
        assertThat(record.entryJson).contains("\"actorId\":\"retention-actor\"");
        assertThat(record.entryJson).contains("\"sequenceNumber\":1");
        assertThat(record.archivedAt).isNotNull();
    }

    @Test
    @Transactional
    void attestations_deletedBeforeEntry_noFkViolation() {
        final UUID subjectId = UUID.randomUUID();
        final TestEntry e = chainedEntry(subjectId, 1, now().minus(40, ChronoUnit.DAYS), null);
        seedAttestation(e.id, subjectId, AttestationVerdict.SOUND);

        // FK ordering wrong → would throw constraint violation
        retentionJob.runRetention();

        assertThat(LedgerEntry.find("subjectId", subjectId).count()).isZero();
        assertThat(LedgerAttestation.find("ledgerEntryId", e.id).count()).isZero();
    }

    @Test
    @Transactional
    void mixedAges_subjectNotArchived() {
        final UUID subjectId = UUID.randomUUID();
        final TestEntry old = chainedEntry(subjectId, 1, now().minus(60, ChronoUnit.DAYS), null);
        chainedEntry(subjectId, 2, now().minus(5, ChronoUnit.DAYS), old.digest);

        retentionJob.runRetention();

        assertThat(LedgerEntry.find("subjectId", subjectId).count()).isEqualTo(2);
        assertThat(LedgerEntryArchiveRecord.find("subjectId", subjectId).count()).isZero();
    }

    @Test
    @Transactional
    void brokenChain_subjectSkipped() {
        final UUID subjectId = UUID.randomUUID();
        final TestEntry e = chainedEntry(subjectId, 1, now().minus(50, ChronoUnit.DAYS), null);
        // Tamper: corrupt the digest so chain verification fails
        e.digest = "000000000000000000000000000000000000000000000000000000000000dead";
        e.persist();

        retentionJob.runRetention();

        assertThat(LedgerEntry.find("subjectId", subjectId).count()).isEqualTo(1);
        assertThat(LedgerEntryArchiveRecord.find("subjectId", subjectId).count()).isZero();
    }

    private TestEntry chainedEntry(final UUID subjectId, final int seq,
            final Instant occurredAt, final String previousHash) {
        final TestEntry e = new TestEntry();
        e.subjectId = subjectId;
        e.sequenceNumber = seq;
        e.entryType = LedgerEntryType.EVENT;
        e.actorId = "retention-actor";
        e.actorType = ActorType.AGENT;
        e.actorRole = "Classifier";
        e.occurredAt = occurredAt;
        e.previousHash = previousHash;
        e.digest = LedgerHashChain.compute(previousHash, e);
        e.persist();
        return e;
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
        att.persist();
    }

    private Instant now() {
        return Instant.now();
    }
}
