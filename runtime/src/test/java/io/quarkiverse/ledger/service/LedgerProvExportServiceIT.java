package io.quarkiverse.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.model.supplement.ComplianceSupplement;
import io.quarkiverse.ledger.runtime.model.supplement.ProvenanceSupplement;
import io.quarkiverse.ledger.runtime.repository.LedgerEntryRepository;
import io.quarkiverse.ledger.runtime.service.LedgerProvExportService;
import io.quarkiverse.ledger.service.supplement.TestEntry;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class LedgerProvExportServiceIT {

    @Inject
    LedgerProvExportService exportService;
    @Inject
    LedgerEntryRepository repo;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TestEntry seed(UUID subjectId, int seq, String actorId) {
        TestEntry e = new TestEntry();
        e.subjectId = subjectId;
        e.sequenceNumber = seq;
        e.entryType = LedgerEntryType.EVENT;
        e.actorId = actorId;
        e.actorType = ActorType.SYSTEM;
        e.actorRole = "Tester";
        return (TestEntry) repo.save(e);
    }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    @Transactional
    void exportSubject_threeEntries_validJsonLd() throws Exception {
        UUID sub = UUID.randomUUID();
        seed(sub, 1, "actor-a");
        seed(sub, 2, "actor-a");
        seed(sub, 3, "actor-b");

        String json = exportService.exportSubject(sub);

        assertThat(json).isNotBlank();
        Map<String, Object> doc = MAPPER.readValue(json, new TypeReference<>() {
        });
        assertThat(doc).containsKey("@context");
        assertThat(doc).containsKey("entity");
        assertThat(doc).containsKey("agent");
        assertThat(doc).containsKey("activity");
        assertThat(doc).containsKey("wasGeneratedBy");
        Map<?, ?> entities = (Map<?, ?>) doc.get("entity");
        assertThat(entities).hasSize(3);
    }

    @Test
    @Transactional
    void exportSubject_complianceSupplement_fieldsInOutput() throws Exception {
        UUID sub = UUID.randomUUID();
        TestEntry e = seed(sub, 1, "actor-a");
        ComplianceSupplement cs = new ComplianceSupplement();
        cs.algorithmRef = "gpt-4o";
        cs.confidenceScore = 0.88;
        e.attach(cs);
        e.persist();

        String json = exportService.exportSubject(sub);

        Map<String, Object> doc = MAPPER.readValue(json, new TypeReference<>() {
        });
        Map<?, ?> entity = (Map<?, ?>) ((Map<?, ?>) doc.get("entity"))
                .get("ledger:entry/" + e.id);
        assertThat(entity.get("ledger:algorithmRef")).isEqualTo("gpt-4o");
        assertThat(entity.get("ledger:confidenceScore")).isEqualTo(0.88);
    }

    @Test
    @Transactional
    void exportSubject_provenanceSupplement_hadPrimarySourcePresent() throws Exception {
        UUID sub = UUID.randomUUID();
        TestEntry e = seed(sub, 1, "actor-a");
        ProvenanceSupplement ps = new ProvenanceSupplement();
        ps.sourceEntityId = "wi-abc";
        ps.sourceEntityType = "WorkItem";
        ps.sourceEntitySystem = "tarkus";
        e.attach(ps);
        e.persist();

        String json = exportService.exportSubject(sub);

        Map<String, Object> doc = MAPPER.readValue(json, new TypeReference<>() {
        });
        assertThat(doc).containsKey("hadPrimarySource");
    }

    // ── end to end ────────────────────────────────────────────────────────────

    @Test
    @Transactional
    void e2e_allRelationsPresent_threeEntrySubject() throws Exception {
        UUID sub = UUID.randomUUID();
        seed(sub, 1, "actor-a");
        seed(sub, 2, "actor-b");
        seed(sub, 3, "actor-a");

        String json = exportService.exportSubject(sub);

        Map<String, Object> doc = MAPPER.readValue(json, new TypeReference<>() {
        });
        // 2 agents (actor-a deduplicated), 3 activities, 2 sequential wasDerivedFrom
        Map<?, ?> agents = (Map<?, ?>) doc.get("agent");
        assertThat(agents).hasSize(2);
        Map<?, ?> wdf = (Map<?, ?>) doc.get("wasDerivedFrom");
        assertThat(wdf).hasSize(2);
    }
}
