package io.casehub.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;
import io.casehub.ledger.runtime.service.GapType;
import io.casehub.ledger.runtime.service.LedgerGapDetected;
import io.casehub.ledger.runtime.service.LedgerHealthJob;
import io.casehub.ledger.runtime.service.LedgerReconciliationSource;
import io.casehub.ledger.service.supplement.TestEntry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

/**
 * Integration tests for {@link LedgerHealthJob} — sequence gap detection and reconciliation.
 */
@QuarkusTest
@TestProfile(LedgerHealthJobIT.HealthTestProfile.class)
class LedgerHealthJobIT {

    public static class HealthTestProfile implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "health-test";
        }
    }

    /**
     * Captures CDI events fired by the health job.
     * Uses a static list so field access from the test bypasses the CDI proxy — same
     * pattern as CountingEnricher.count in LedgerEnricherPipelineIT.
     */
    @ApplicationScoped
    static class GapEventCapture {
        static final List<LedgerGapDetected> EVENTS = new ArrayList<>();

        void onGap(@Observes final LedgerGapDetected event) {
            EVENTS.add(event);
        }
    }

    /**
     * Test reconciliation source with static state for the same proxy-bypass reason.
     */
    @ApplicationScoped
    static class TestReconciliationSource implements LedgerReconciliationSource {
        static long domainCount = 0;
        static long ledgerCount = 0;
        static boolean active = false;

        @Override
        public String subjectType() {
            return "TestEntity";
        }

        @Override
        public long countDomainEntities() {
            return domainCount;
        }

        @Override
        public long countLedgerEntries() {
            return ledgerCount;
        }

        @Override
        public boolean isActive() {
            return active;
        }
    }

    @Inject LedgerHealthJob healthJob;
    @Inject LedgerEntryRepository repo;

    @BeforeEach
    void reset() {
        GapEventCapture.EVENTS.clear();
        TestReconciliationSource.active = false;
        TestReconciliationSource.domainCount = 0;
        TestReconciliationSource.ledgerCount = 0;
    }

    // ── Happy path: contiguous sequences — no event ───────────────────────────

    @Test
    @Transactional
    void contiguousSequences_noGapEvent() {
        final UUID subjectId = UUID.randomUUID();
        entry(subjectId, 1);
        entry(subjectId, 2);
        entry(subjectId, 3);

        healthJob.run();

        final List<LedgerGapDetected> gaps = GapEventCapture.EVENTS.stream()
                .filter(e -> e.subjectId().equals(subjectId.toString()))
                .filter(e -> e.type() == GapType.SEQUENCE_GAP)
                .toList();
        assertThat(gaps).isEmpty();
    }

    // ── Correctness: sequence gap detected ────────────────────────────────────

    @Test
    @Transactional
    void sequenceGap_eventFired() {
        final UUID subjectId = UUID.randomUUID();
        entry(subjectId, 1);
        entry(subjectId, 2);
        entry(subjectId, 4); // gap at 3

        healthJob.run();

        final List<LedgerGapDetected> gaps = GapEventCapture.EVENTS.stream()
                .filter(e -> e.subjectId().equals(subjectId.toString()))
                .filter(e -> e.type() == GapType.SEQUENCE_GAP)
                .toList();
        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).expectedCount()).isEqualTo(4); // max-min+1 = 4-1+1 = 4
        assertThat(gaps.get(0).actualCount()).isEqualTo(3);
    }

    // ── Correctness: only gapped subject fires event ───────────────────────────

    @Test
    @Transactional
    void multipleSubjects_onlyGappedFires() {
        final UUID cleanSubjectId = UUID.randomUUID();
        entry(cleanSubjectId, 1);
        entry(cleanSubjectId, 2);

        final UUID gappedSubjectId = UUID.randomUUID();
        entry(gappedSubjectId, 1);
        entry(gappedSubjectId, 3); // gap at 2

        healthJob.run();

        final List<LedgerGapDetected> gapEvents = GapEventCapture.EVENTS.stream()
                .filter(e -> e.type() == GapType.SEQUENCE_GAP)
                .toList();

        assertThat(gapEvents).anyMatch(e -> e.subjectId().equals(gappedSubjectId.toString()));
        assertThat(gapEvents).noneMatch(e -> e.subjectId().equals(cleanSubjectId.toString()));
    }

    // ── Robustness: single entry per subject — no gap possible ───────────────

    @Test
    @Transactional
    void singleEntry_noGapEvent() {
        // A subject with one entry always satisfies COUNT=1 = MAX-MIN+1=1, so no gap.
        final UUID subjectId = UUID.randomUUID();
        entry(subjectId, 1);

        healthJob.run();

        assertThat(GapEventCapture.EVENTS.stream()
                .filter(e -> e.subjectId().equals(subjectId.toString()))
                .filter(e -> e.type() == GapType.SEQUENCE_GAP)
                .toList()).isEmpty();
    }

    // ── Robustness: reconciliation mismatch fires event ───────────────────────

    @Test
    void reconciliationMismatch_eventFired() {
        TestReconciliationSource.active = true;
        TestReconciliationSource.domainCount = 5;
        TestReconciliationSource.ledgerCount = 3;

        healthJob.run();

        final List<LedgerGapDetected> reconciliationEvents = GapEventCapture.EVENTS.stream()
                .filter(e -> e.type() == GapType.RECONCILIATION_MISMATCH)
                .toList();
        assertThat(reconciliationEvents).hasSize(1);
        assertThat(reconciliationEvents.get(0).expectedCount()).isEqualTo(5);
        assertThat(reconciliationEvents.get(0).actualCount()).isEqualTo(3);
    }

    // ── Robustness: inactive reconciliation source — no event ─────────────────

    @Test
    void inactiveReconciliationSource_noEvent() {
        TestReconciliationSource.active = false;
        TestReconciliationSource.domainCount = 10;
        TestReconciliationSource.ledgerCount = 0; // mismatch, but inactive

        healthJob.run();

        assertThat(GapEventCapture.EVENTS.stream()
                .filter(e -> e.type() == GapType.RECONCILIATION_MISMATCH)
                .toList()).isEmpty();
    }

    // ── Robustness: matching reconciliation counts — no event ─────────────────

    @Test
    void reconciliationMatch_noEvent() {
        TestReconciliationSource.active = true;
        TestReconciliationSource.domainCount = 4;
        TestReconciliationSource.ledgerCount = 4;

        healthJob.run();

        assertThat(GapEventCapture.EVENTS.stream()
                .filter(e -> e.type() == GapType.RECONCILIATION_MISMATCH)
                .toList()).isEmpty();
    }

    // ── fixture ───────────────────────────────────────────────────────────────

    private void entry(final UUID subjectId, final int seq) {
        final TestEntry e = new TestEntry();
        e.subjectId = subjectId;
        e.sequenceNumber = seq;
        e.entryType = LedgerEntryType.EVENT;
        e.actorId = "health-actor";
        e.actorType = ActorType.AGENT;
        e.actorRole = "HealthTester";
        e.occurredAt = Instant.now();
        repo.save(e);
    }
}
