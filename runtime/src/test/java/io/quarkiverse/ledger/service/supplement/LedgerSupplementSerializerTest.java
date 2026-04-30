package io.casehub.ledger.service.supplement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.runtime.model.supplement.ComplianceSupplement;
import io.casehub.ledger.runtime.model.supplement.LedgerSupplementSerializer;
import io.casehub.ledger.runtime.model.supplement.ProvenanceSupplement;

/**
 * Unit tests for {@link LedgerSupplementSerializer} — no Quarkus runtime, no CDI.
 */
class LedgerSupplementSerializerTest {

    @Test
    void toJson_nullList_returnsNull() {
        assertThat(LedgerSupplementSerializer.toJson(null)).isNull();
    }

    @Test
    void toJson_emptyList_returnsNull() {
        assertThat(LedgerSupplementSerializer.toJson(List.of())).isNull();
    }

    @Test
    void toJson_complianceSupplement_containsTypeKey() {
        final ComplianceSupplement cs = new ComplianceSupplement();
        cs.algorithmRef = "model-v1";

        final String json = LedgerSupplementSerializer.toJson(List.of(cs));

        assertThat(json).isNotNull();
        assertThat(json).contains("\"COMPLIANCE\"");
        assertThat(json).contains("\"algorithmRef\":\"model-v1\"");
    }

    @Test
    void toJson_nullFieldsOmitted() {
        final ComplianceSupplement cs = new ComplianceSupplement();
        cs.algorithmRef = "rule-engine-v2";
        // confidenceScore, contestationUri, humanOverrideAvailable all null

        final String json = LedgerSupplementSerializer.toJson(List.of(cs));

        assertThat(json).contains("algorithmRef");
        assertThat(json).doesNotContain("confidenceScore");
        assertThat(json).doesNotContain("contestationUri");
        assertThat(json).doesNotContain("humanOverrideAvailable");
    }

    @Test
    void toJson_allComplianceFields_serialisedCorrectly() {
        final ComplianceSupplement cs = new ComplianceSupplement();
        cs.planRef = "policy-2026-q1";
        cs.rationale = "Risk threshold exceeded";
        cs.algorithmRef = "gpt-4o";
        cs.confidenceScore = 0.92;
        cs.contestationUri = "https://example.com/challenge";
        cs.humanOverrideAvailable = true;
        cs.decisionContext = "{\"riskScore\":77}";

        final String json = LedgerSupplementSerializer.toJson(List.of(cs));

        assertThat(json).contains("\"planRef\":\"policy-2026-q1\"");
        assertThat(json).contains("\"rationale\":\"Risk threshold exceeded\"");
        assertThat(json).contains("\"algorithmRef\":\"gpt-4o\"");
        assertThat(json).contains("\"confidenceScore\":0.92");
        assertThat(json).contains("\"contestationUri\":\"https://example.com/challenge\"");
        assertThat(json).contains("\"humanOverrideAvailable\":true");
        assertThat(json).contains("\"decisionContext\":\"{\\\"riskScore\\\":77}\"");
    }

    @Test
    void toJson_provenanceSupplement_serialisedCorrectly() {
        final ProvenanceSupplement ps = new ProvenanceSupplement();
        ps.sourceEntityId = "wf-123";
        ps.sourceEntityType = "Flow:WorkflowInstance";
        ps.sourceEntitySystem = "quarkus-flow";

        final String json = LedgerSupplementSerializer.toJson(List.of(ps));

        assertThat(json).contains("\"PROVENANCE\"");
        assertThat(json).contains("\"sourceEntitySystem\":\"quarkus-flow\"");
    }

    @Test
    void toJson_multipleSupplements_allPresent() {
        final ComplianceSupplement cs = new ComplianceSupplement();
        cs.algorithmRef = "v1";
        final ProvenanceSupplement ps = new ProvenanceSupplement();
        ps.sourceEntitySystem = "quarkus-flow";

        final String json = LedgerSupplementSerializer.toJson(List.of(cs, ps));

        assertThat(json).contains("\"COMPLIANCE\"");
        assertThat(json).contains("\"PROVENANCE\"");
        assertThat(json).contains("algorithmRef");
        assertThat(json).contains("quarkus-flow");
    }
}
