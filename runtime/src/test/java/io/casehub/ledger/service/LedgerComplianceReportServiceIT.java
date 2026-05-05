package io.casehub.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;
import io.casehub.ledger.runtime.service.ComplianceReport;
import io.casehub.ledger.runtime.service.LedgerComplianceReportService;
import io.casehub.ledger.runtime.service.ReportFormat;
import io.casehub.ledger.runtime.model.supplement.ComplianceSupplement;
import io.casehub.ledger.runtime.model.supplement.ProvenanceSupplement;
import io.casehub.ledger.service.supplement.TestEntry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

/**
 * Integration tests for {@link LedgerComplianceReportService}.
 */
@QuarkusTest
@TestProfile(LedgerComplianceReportServiceIT.ComplianceTestProfile.class)
class LedgerComplianceReportServiceIT {

    public static class ComplianceTestProfile implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "compliance-test";
        }
    }

    @Inject LedgerComplianceReportService reportService;
    @Inject LedgerEntryRepository repo;

    // ── Happy path: actor report with compliance supplements ─────────────────

    @Test
    @Transactional
    void actorReport_onlyEntriesWithComplianceSupplement() {
        final String actorId = "report-actor-" + UUID.randomUUID();
        final Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
        final Instant to = Instant.now().plus(1, ChronoUnit.HOURS);

        // 2 entries with ComplianceSupplement, 1 bare entry
        final TestEntry e1 = entryWithCompliance(actorId, "algorithm-v1", 0.9);
        final TestEntry e2 = entryWithCompliance(actorId, "algorithm-v2", 0.8);
        final TestEntry e3 = bareEntry(actorId);

        final ComplianceReport report = reportService.reportForActor(actorId, from, to);

        assertThat(report.actorId()).isEqualTo(actorId);
        assertThat(report.totalDecisions()).isEqualTo(2);
        assertThat(report.decisions()).hasSize(2);
        assertThat(report.decisions()).anyMatch(d -> "algorithm-v1".equals(d.algorithmRef()));
        assertThat(report.decisions()).anyMatch(d -> "algorithm-v2".equals(d.algorithmRef()));
    }

    // ── Happy path: actor report with empty time range ────────────────────────

    @Test
    @Transactional
    void actorReport_emptyRange_zeroDecisions() {
        final String actorId = "report-empty-" + UUID.randomUUID();
        final Instant from = Instant.now().minus(2, ChronoUnit.DAYS);
        final Instant to = Instant.now().minus(1, ChronoUnit.DAYS); // yesterday — no entries

        entryWithCompliance(actorId, "alg-v1", 0.9);

        final ComplianceReport report = reportService.reportForActor(actorId, from, to);

        assertThat(report.totalDecisions()).isEqualTo(0);
        assertThat(report.decisions()).isEmpty();
    }

    // ── Happy path: subject report with Merkle root ───────────────────────────

    @Test
    @Transactional
    void subjectReport_merkleRoot_populated() {
        final UUID subjectId = UUID.randomUUID();
        final String actorId = "subject-report-" + UUID.randomUUID();
        final Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
        final Instant to = Instant.now().plus(1, ChronoUnit.HOURS);

        entryWithComplianceForSubject(subjectId, actorId, "alg-v3", 0.7);

        final ComplianceReport report = reportService.reportForSubject(subjectId, from, to);

        assertThat(report.subjectId()).isEqualTo(subjectId);
        assertThat(report.totalDecisions()).isEqualTo(1);
        assertThat(report.merkleRootAtGeneration()).isNotNull();
    }

    // ── Correctness: time range filtering for subject reports ─────────────────

    @Test
    @Transactional
    void subjectReport_timeRange_filtersOldEntries() {
        final UUID subjectId = UUID.randomUUID();
        final String actorId = "subject-filter-" + UUID.randomUUID();

        // Entry 1: within range
        entryWithComplianceForSubject(subjectId, actorId, "alg-new", 0.9);
        // Entry 2: outside range — we test the range filter by querying a past window
        final Instant pastFrom = Instant.now().minus(2, ChronoUnit.DAYS);
        final Instant pastTo = Instant.now().minus(1, ChronoUnit.DAYS);

        final ComplianceReport report = reportService.reportForSubject(subjectId, pastFrom, pastTo);

        assertThat(report.totalDecisions()).isEqualTo(0);
    }

    // ── Correctness: ProvenanceSupplement fields in DecisionRecord ────────────

    @Test
    @Transactional
    void actorReport_provenanceSupplement_populatedInDecisionRecord() {
        final String actorId = "provenance-report-" + UUID.randomUUID();
        final Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
        final Instant to = Instant.now().plus(1, ChronoUnit.HOURS);

        entryWithComplianceAndProvenance(actorId, "alg-v4", 0.85, "WorkItem", UUID.randomUUID());

        final ComplianceReport report = reportService.reportForActor(actorId, from, to);

        assertThat(report.decisions()).hasSize(1);
        assertThat(report.decisions().get(0).sourceEntityType()).isEqualTo("WorkItem");
        assertThat(report.decisions().get(0).sourceEntityId()).isNotNull();
    }

    // ── Format: CSV has header and rows ──────────────────────────────────────

    @Test
    @Transactional
    void actorReport_csvFormat_hasHeaderAndRows() {
        final String actorId = "csv-actor-" + UUID.randomUUID();
        final Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
        final Instant to = Instant.now().plus(1, ChronoUnit.HOURS);

        entryWithCompliance(actorId, "alg-csv", 0.95);

        final ComplianceReport report = reportService.reportForActor(actorId, from, to);
        final String csv = report.format(ReportFormat.CSV);

        assertThat(csv).contains("entryId");
        assertThat(csv).contains("algorithmRef");
        assertThat(csv).contains("alg-csv");
    }

    // ── Format: JSON-LD has @context ─────────────────────────────────────────

    @Test
    @Transactional
    void actorReport_jsonLdFormat_hasAtContext() {
        final String actorId = "jsonld-actor-" + UUID.randomUUID();
        final Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
        final Instant to = Instant.now().plus(1, ChronoUnit.HOURS);

        entryWithCompliance(actorId, "alg-jsonld", 0.9);

        final ComplianceReport report = reportService.reportForActor(actorId, from, to);
        final String jsonLd = report.format(ReportFormat.JSON_LD);

        assertThat(jsonLd).contains("@context");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private TestEntry entryWithCompliance(final String actorId, final String algorithmRef, final double confidence) {
        final TestEntry e = base(UUID.randomUUID(), actorId);
        final ComplianceSupplement cs = new ComplianceSupplement();
        cs.algorithmRef = algorithmRef;
        cs.confidenceScore = confidence;
        cs.contestationUri = "https://example.com/challenge";
        cs.humanOverrideAvailable = true;
        e.attach(cs);
        return (TestEntry) repo.save(e);
    }

    private TestEntry entryWithComplianceForSubject(final UUID subjectId, final String actorId,
            final String algorithmRef, final double confidence) {
        final TestEntry e = base(subjectId, actorId);
        final ComplianceSupplement cs = new ComplianceSupplement();
        cs.algorithmRef = algorithmRef;
        cs.confidenceScore = confidence;
        e.attach(cs);
        return (TestEntry) repo.save(e);
    }

    private TestEntry entryWithComplianceAndProvenance(final String actorId, final String algorithmRef,
            final double confidence, final String entityType, final UUID entityId) {
        final TestEntry e = base(UUID.randomUUID(), actorId);
        final ComplianceSupplement cs = new ComplianceSupplement();
        cs.algorithmRef = algorithmRef;
        cs.confidenceScore = confidence;
        e.attach(cs);
        final ProvenanceSupplement ps = new ProvenanceSupplement();
        ps.sourceEntityType = entityType;
        ps.sourceEntityId = entityId.toString();
        e.attach(ps);
        return (TestEntry) repo.save(e);
    }

    private TestEntry bareEntry(final String actorId) {
        return (TestEntry) repo.save(base(UUID.randomUUID(), actorId));
    }

    private static TestEntry base(final UUID subjectId, final String actorId) {
        final TestEntry e = new TestEntry();
        e.subjectId = subjectId;
        e.sequenceNumber = 1;
        e.entryType = LedgerEntryType.EVENT;
        e.actorId = actorId;
        e.actorType = ActorType.AGENT;
        e.actorRole = "ComplianceReporter";
        e.occurredAt = Instant.now();
        return e;
    }
}
