package io.quarkiverse.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.model.supplement.ComplianceSupplement;
import io.quarkiverse.ledger.runtime.model.supplement.ProvenanceSupplement;
import io.quarkiverse.ledger.runtime.service.LedgerProvSerializer;
import io.quarkiverse.ledger.service.supplement.TestEntry;

class LedgerProvSerializerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant FIXED = Instant.parse("2026-04-18T10:00:00Z");

    private TestEntry entry(UUID subjectId, int seq, String actorId) {
        TestEntry e = new TestEntry();
        e.id = UUID.randomUUID();
        e.subjectId = subjectId;
        e.sequenceNumber = seq;
        e.entryType = LedgerEntryType.EVENT;
        e.actorId = actorId;
        e.actorType = ActorType.AGENT;
        e.actorRole = "Tester";
        e.occurredAt = FIXED;
        return e;
    }

    private Map<String, Object> parse(String json) throws Exception {
        return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    // ── @context ─────────────────────────────────────────────────────────────

    @Test
    void output_hasContextWithThreeKeys() throws Exception {
        UUID sub = UUID.randomUUID();
        String json = LedgerProvSerializer.toProvJsonLd(sub, List.of(entry(sub, 1, "a1")));
        Map<String, Object> doc = parse(json);
        Map<String, Object> ctx = asMap(doc.get("@context"));
        assertThat(ctx).containsOnlyKeys("prov", "ledger", "xsd");
        assertThat(ctx.get("prov")).isEqualTo("http://www.w3.org/ns/prov#");
        assertThat(ctx.get("ledger")).isEqualTo("http://quarkiverse.io/ledger#");
        assertThat(ctx.get("xsd")).isEqualTo("http://www.w3.org/2001/XMLSchema#");
    }

    // ── entity ───────────────────────────────────────────────────────────────

    @Test
    void singleEntry_producesOneEntity() throws Exception {
        UUID sub = UUID.randomUUID();
        TestEntry e = entry(sub, 1, "actor-a");
        String json = LedgerProvSerializer.toProvJsonLd(sub, List.of(e));
        Map<String, Object> doc = parse(json);
        Map<String, Object> entities = asMap(doc.get("entity"));
        assertThat(entities).hasSize(1);
        assertThat(entities).containsKey("ledger:entry/" + e.id);
    }

    @Test
    void entity_hasRequiredProvDmFields() throws Exception {
        UUID sub = UUID.randomUUID();
        TestEntry e = entry(sub, 1, "actor-a");
        Map<String, Object> doc = parse(LedgerProvSerializer.toProvJsonLd(sub, List.of(e)));
        Map<String, Object> entity = asMap(asMap(doc.get("entity")).get("ledger:entry/" + e.id));
        assertThat(entity.get("prov:type")).isEqualTo("ledger:LedgerEntry");
        assertThat(entity.get("ledger:subjectId")).isEqualTo(sub.toString());
        assertThat(entity.get("ledger:sequenceNumber")).isEqualTo(1);
        assertThat(entity.get("ledger:entryType")).isEqualTo("EVENT");
        assertThat(entity).containsKey("prov:generatedAtTime");
    }

    @Test
    void entity_nullFieldsOmitted() throws Exception {
        UUID sub = UUID.randomUUID();
        TestEntry e = entry(sub, 1, "actor-a");
        e.traceId = null;
        e.digest = null;
        Map<String, Object> doc = parse(LedgerProvSerializer.toProvJsonLd(sub, List.of(e)));
        Map<String, Object> entity = asMap(asMap(doc.get("entity")).get("ledger:entry/" + e.id));
        assertThat(entity).doesNotContainKey("ledger:traceId");
        assertThat(entity).doesNotContainKey("ledger:digest");
    }

    // ── agent ─────────────────────────────────────────────────────────────────

    @Test
    void singleEntry_producesOneAgent() throws Exception {
        UUID sub = UUID.randomUUID();
        String json = LedgerProvSerializer.toProvJsonLd(sub, List.of(entry(sub, 1, "agent-007")));
        Map<String, Object> agents = asMap(parse(json).get("agent"));
        assertThat(agents).hasSize(1);
        assertThat(agents).containsKey("ledger:actor/agent-007");
    }

    @Test
    void sameActorId_acrossEntries_deduplicatedToOneAgent() throws Exception {
        UUID sub = UUID.randomUUID();
        TestEntry e1 = entry(sub, 1, "shared-actor");
        TestEntry e2 = entry(sub, 2, "shared-actor");
        String json = LedgerProvSerializer.toProvJsonLd(sub, List.of(e1, e2));
        Map<String, Object> agents = asMap(parse(json).get("agent"));
        assertThat(agents).hasSize(1);
    }

    @Test
    void nullActorId_noAgentNode_noWasAssociatedWith() throws Exception {
        UUID sub = UUID.randomUUID();
        TestEntry e = entry(sub, 1, null);
        Map<String, Object> doc = parse(LedgerProvSerializer.toProvJsonLd(sub, List.of(e)));
        assertThat(doc).doesNotContainKey("agent");
        assertThat(doc).doesNotContainKey("wasAssociatedWith");
    }

    // ── relations ─────────────────────────────────────────────────────────────

    @Test
    void wasGeneratedBy_alwaysPresent() throws Exception {
        UUID sub = UUID.randomUUID();
        TestEntry e = entry(sub, 1, "a");
        Map<String, Object> doc = parse(LedgerProvSerializer.toProvJsonLd(sub, List.of(e)));
        Map<String, Object> wgb = asMap(doc.get("wasGeneratedBy"));
        assertThat(wgb).hasSize(1);
        Map<String, Object> rel = asMap(wgb.values().iterator().next());
        assertThat(rel.get("prov:entity")).isEqualTo("ledger:entry/" + e.id);
        assertThat(rel.get("prov:activity")).isEqualTo("ledger:activity/" + e.id);
    }

    @Test
    void twoEntries_wasDerivedFrom_sequentialChain() throws Exception {
        UUID sub = UUID.randomUUID();
        TestEntry e1 = entry(sub, 1, "a");
        TestEntry e2 = entry(sub, 2, "a");
        Map<String, Object> doc = parse(LedgerProvSerializer.toProvJsonLd(sub, List.of(e1, e2)));
        Map<String, Object> wdf = asMap(doc.get("wasDerivedFrom"));
        assertThat(wdf).isNotEmpty();
        boolean hasChain = wdf.values().stream().anyMatch(v -> {
            Map<String, Object> rel = asMap(v);
            return rel.get("prov:generatedEntity").equals("ledger:entry/" + e2.id)
                    && rel.get("prov:usedEntity").equals("ledger:entry/" + e1.id);
        });
        assertThat(hasChain).isTrue();
    }

    @Test
    void causedByEntryId_producesWasDerivedFrom() throws Exception {
        UUID sub = UUID.randomUUID();
        UUID externalEntryId = UUID.randomUUID();
        TestEntry e = entry(sub, 1, "a");
        e.causedByEntryId = externalEntryId;
        Map<String, Object> doc = parse(LedgerProvSerializer.toProvJsonLd(sub, List.of(e)));
        Map<String, Object> wdf = asMap(doc.get("wasDerivedFrom"));
        assertThat(wdf).isNotEmpty();
        boolean hasCausal = wdf.values().stream().anyMatch(v -> {
            Map<String, Object> rel = asMap(v);
            return rel.get("prov:generatedEntity").equals("ledger:entry/" + e.id)
                    && rel.get("prov:usedEntity").equals("ledger:entry/" + externalEntryId);
        });
        assertThat(hasCausal).isTrue();
    }

    // ── ComplianceSupplement ──────────────────────────────────────────────────

    @Test
    void complianceSupplement_allFieldsMappedToEntity() throws Exception {
        UUID sub = UUID.randomUUID();
        TestEntry e = entry(sub, 1, "a");
        ComplianceSupplement cs = new ComplianceSupplement();
        cs.algorithmRef = "gpt-4o";
        cs.confidenceScore = 0.92;
        cs.contestationUri = "https://example.com/challenge";
        cs.humanOverrideAvailable = true;
        cs.planRef = "policy-v9";
        cs.rationale = "threshold exceeded";
        cs.evidence = "{\"score\":0.92}";
        cs.decisionContext = "{\"input\":\"test\"}";
        e.attach(cs);
        Map<String, Object> doc = parse(LedgerProvSerializer.toProvJsonLd(sub, List.of(e)));
        Map<String, Object> entity = asMap(asMap(doc.get("entity")).get("ledger:entry/" + e.id));
        assertThat(entity.get("ledger:algorithmRef")).isEqualTo("gpt-4o");
        assertThat(entity.get("ledger:confidenceScore")).isEqualTo(0.92);
        assertThat(entity.get("ledger:contestationUri")).isEqualTo("https://example.com/challenge");
        assertThat(entity.get("ledger:humanOverrideAvailable")).isEqualTo(true);
        assertThat(entity.get("ledger:planRef")).isEqualTo("policy-v9");
        assertThat(entity.get("ledger:rationale")).isEqualTo("threshold exceeded");
        assertThat(entity.get("ledger:evidence")).isEqualTo("{\"score\":0.92}");
        assertThat(entity.get("ledger:decisionContext")).isEqualTo("{\"input\":\"test\"}");
    }

    @Test
    void complianceSupplement_nullFieldsOmitted() throws Exception {
        UUID sub = UUID.randomUUID();
        TestEntry e = entry(sub, 1, "a");
        ComplianceSupplement cs = new ComplianceSupplement();
        cs.algorithmRef = "gpt-4o";
        e.attach(cs);
        Map<String, Object> doc = parse(LedgerProvSerializer.toProvJsonLd(sub, List.of(e)));
        Map<String, Object> entity = asMap(asMap(doc.get("entity")).get("ledger:entry/" + e.id));
        assertThat(entity.get("ledger:algorithmRef")).isEqualTo("gpt-4o");
        assertThat(entity).doesNotContainKey("ledger:confidenceScore");
        assertThat(entity).doesNotContainKey("ledger:planRef");
    }

    // ── ProvenanceSupplement ──────────────────────────────────────────────────

    @Test
    void provenanceSupplement_producesHadPrimarySource() throws Exception {
        UUID sub = UUID.randomUUID();
        TestEntry e = entry(sub, 1, "a");
        ProvenanceSupplement ps = new ProvenanceSupplement();
        ps.sourceEntityId = "wi-uuid-123";
        ps.sourceEntityType = "WorkItem";
        ps.sourceEntitySystem = "tarkus";
        e.attach(ps);
        Map<String, Object> doc = parse(LedgerProvSerializer.toProvJsonLd(sub, List.of(e)));
        Map<String, Object> hps = asMap(doc.get("hadPrimarySource"));
        assertThat(hps).isNotEmpty();
        Map<String, Object> rel = asMap(hps.values().iterator().next());
        assertThat(rel.get("prov:entity")).isEqualTo("ledger:entry/" + e.id);
        assertThat(rel.get("prov:hadPrimarySource"))
                .isEqualTo("ledger:external/WorkItem/tarkus/wi-uuid-123");
    }
}
