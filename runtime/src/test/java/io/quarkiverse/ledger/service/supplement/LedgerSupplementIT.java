package io.quarkiverse.ledger.service.supplement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import jakarta.transaction.Transactional;

import org.junit.jupiter.api.Test;

import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.model.supplement.ComplianceSupplement;
import io.quarkiverse.ledger.runtime.model.supplement.LedgerSupplement;
import io.quarkiverse.ledger.runtime.model.supplement.ProvenanceSupplement;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for the LedgerSupplement system.
 *
 * <p>
 * Verifies persistence, lazy loading, and zero-touch behaviour using a
 * test-only {@link TestEntry} subclass backed by an H2 in-memory database.
 */
@QuarkusTest
class LedgerSupplementIT {

    // ── happy path: no supplements ────────────────────────────────────────────

    @Test
    @Transactional
    void bareEntry_supplementTablesNotTouched() {
        final TestEntry entry = bareEntry();
        entry.persist();

        final long count = LedgerSupplement.count("ledgerEntry.id", entry.id);
        assertThat(count).isZero();
        assertThat(entry.supplementJson).isNull();
    }

    // ── happy path: ComplianceSupplement ─────────────────────────────────────

    @Test
    @Transactional
    void complianceSupplement_persistsAndLoadsAllFields() {
        final TestEntry entry = bareEntry();

        final ComplianceSupplement cs = new ComplianceSupplement();
        cs.planRef = "policy-v3";
        cs.rationale = "Risk threshold exceeded";
        cs.algorithmRef = "classifier-v2";
        cs.confidenceScore = 0.91;
        cs.contestationUri = "https://example.com/challenge";
        cs.humanOverrideAvailable = true;
        cs.decisionContext = "{\"score\":88}";
        entry.attach(cs);
        entry.persist();

        final TestEntry found = TestEntry.findById(entry.id);
        assertThat(found).isNotNull();
        assertThat(found.supplementJson).isNotNull();
        assertThat(found.supplementJson).contains("classifier-v2");

        final ComplianceSupplement loaded = found.compliance().orElseThrow();
        assertThat(loaded.planRef).isEqualTo("policy-v3");
        assertThat(loaded.rationale).isEqualTo("Risk threshold exceeded");
        assertThat(loaded.algorithmRef).isEqualTo("classifier-v2");
        assertThat(loaded.confidenceScore).isEqualTo(0.91);
        assertThat(loaded.contestationUri).isEqualTo("https://example.com/challenge");
        assertThat(loaded.humanOverrideAvailable).isTrue();
        assertThat(loaded.decisionContext).isEqualTo("{\"score\":88}");
    }

    // ── happy path: supplementJson for reads without join ─────────────────────

    @Test
    @Transactional
    void supplementJson_containsTwoSupplementTypes() {
        final TestEntry entry = bareEntry();

        final ComplianceSupplement cs = new ComplianceSupplement();
        cs.algorithmRef = "v1";
        entry.attach(cs);

        final ProvenanceSupplement ps = new ProvenanceSupplement();
        ps.sourceEntitySystem = "quarkus-flow";
        entry.attach(ps);
        entry.persist();

        final TestEntry found = TestEntry.findById(entry.id);
        assertThat(found.supplementJson).contains("\"COMPLIANCE\"");
        assertThat(found.supplementJson).contains("\"PROVENANCE\"");
        assertThat(found.supplementJson).contains("quarkus-flow");
    }

    // ── happy path: ProvenanceSupplement ─────────────────────────────────────

    @Test
    @Transactional
    void provenanceSupplement_persistsAndLoads() {
        final TestEntry entry = bareEntry();

        final ProvenanceSupplement ps = new ProvenanceSupplement();
        ps.sourceEntityId = "wf-42";
        ps.sourceEntityType = "Flow:WorkflowInstance";
        ps.sourceEntitySystem = "quarkus-flow";
        entry.attach(ps);
        entry.persist();

        final ProvenanceSupplement loaded = TestEntry.<TestEntry> findById(entry.id)
                .provenance().orElseThrow();
        assertThat(loaded.sourceEntityId).isEqualTo("wf-42");
        assertThat(loaded.sourceEntitySystem).isEqualTo("quarkus-flow");
    }

    // ── attach replaces existing supplement of same type ──────────────────────

    @Test
    @Transactional
    void attach_replacesExistingSupplementOfSameType() {
        final TestEntry entry = bareEntry();

        final ComplianceSupplement first = new ComplianceSupplement();
        first.algorithmRef = "v1";
        entry.attach(first);

        final ComplianceSupplement second = new ComplianceSupplement();
        second.algorithmRef = "v2";
        entry.attach(second);

        assertThat(entry.supplements).hasSize(1);
        assertThat(entry.compliance().map(c -> c.algorithmRef).orElse(null))
                .isEqualTo("v2");
        assertThat(entry.supplementJson).contains("v2");
        assertThat(entry.supplementJson).doesNotContain("\"v1\"");
    }

    // ── supplementJson is null for bare entry ─────────────────────────────────

    @Test
    @Transactional
    void bareEntry_supplementJson_isNull() {
        final TestEntry entry = bareEntry();
        entry.persist();

        final TestEntry found = TestEntry.findById(entry.id);
        assertThat(found.supplementJson).isNull();
    }

    // ── fixture ───────────────────────────────────────────────────────────────

    private TestEntry bareEntry() {
        final TestEntry e = new TestEntry();
        e.subjectId = UUID.randomUUID();
        e.sequenceNumber = 1;
        e.entryType = LedgerEntryType.EVENT;
        e.actorId = "test-actor";
        e.actorType = ActorType.SYSTEM;
        e.actorRole = "TestRole";
        return e;
    }
}
