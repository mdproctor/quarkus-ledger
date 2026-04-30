package io.casehub.ledger.example.prov;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ProvDmExportIT {

    @Inject
    ProvDmExportExample example;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void happyPath_allSupplementTypes_validProvJsonLd() throws Exception {
        String json = example.runHappyPath(UUID.randomUUID());

        assertThat(json).isNotBlank();
        Map<String, Object> doc = MAPPER.readValue(json, new TypeReference<>() {
        });

        // @context always exactly 3 keys
        @SuppressWarnings("unchecked")
        Map<String, Object> ctx = (Map<String, Object>) doc.get("@context");
        assertThat(ctx).containsOnlyKeys("prov", "ledger", "xsd");

        // 3 entities
        assertThat((Map<?, ?>) doc.get("entity")).hasSize(3);

        // 2 agents — classifier-agent-v2 (deduplicated across entries 1+3) + orchestrator-system
        @SuppressWarnings("unchecked")
        Map<String, Object> agents = (Map<String, Object>) doc.get("agent");
        assertThat(agents).hasSize(2);
        assertThat(agents).containsKey("ledger:actor/classifier-agent-v2");
        assertThat(agents).containsKey("ledger:actor/orchestrator-system");

        // 3 activities, 3 wasGeneratedBy, 3 wasAssociatedWith
        assertThat((Map<?, ?>) doc.get("activity")).hasSize(3);
        assertThat((Map<?, ?>) doc.get("wasGeneratedBy")).hasSize(3);
        assertThat((Map<?, ?>) doc.get("wasAssociatedWith")).hasSize(3);

        // wasDerivedFrom: 2 sequential (2->1, 3->2) + 1 causal (3->1) = 3 total
        assertThat((Map<?, ?>) doc.get("wasDerivedFrom")).hasSize(3);

        // hadPrimarySource from entry 2's ProvenanceSupplement
        assertThat(doc).containsKey("hadPrimarySource");

        // ComplianceSupplement on entry 1
        Map<?, ?> entry1 = ((Map<?, ?>) doc.get("entity")).values().stream()
                .map(v -> (Map<?, ?>) v)
                .filter(e -> "gpt-4o".equals(e.get("ledger:algorithmRef")))
                .findFirst().orElseThrow(() -> new AssertionError("Entry with algorithmRef not found"));
        assertThat(entry1.get("ledger:confidenceScore")).isEqualTo(0.94);
        assertThat(entry1.get("ledger:humanOverrideAvailable")).isEqualTo(true);
        assertThat(entry1.get("ledger:planRef")).isEqualTo("classification-policy-v3");
    }

    @Test
    void happyPath_contextKeys_exactlyProvLedgerXsd() throws Exception {
        String json = example.runHappyPath(UUID.randomUUID());
        Map<String, Object> doc = MAPPER.readValue(json, new TypeReference<>() {
        });
        Map<?, ?> ctx = (Map<?, ?>) doc.get("@context");
        assertThat(ctx.get("prov")).isEqualTo("http://www.w3.org/ns/prov#");
        assertThat(ctx.get("ledger")).isEqualTo("http://quarkiverse.io/ledger#");
        assertThat(ctx.get("xsd")).isEqualTo("http://www.w3.org/2001/XMLSchema#");
    }
}
