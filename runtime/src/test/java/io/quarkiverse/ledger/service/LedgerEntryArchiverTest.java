package io.quarkiverse.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.model.AttestationVerdict;
import io.quarkiverse.ledger.runtime.model.LedgerAttestation;
import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.service.LedgerEntryArchiver;

/**
 * Unit tests for {@link LedgerEntryArchiver} — no Quarkus runtime, no CDI.
 */
class LedgerEntryArchiverTest {

    private static class TestEntry extends LedgerEntry {
    }

    private TestEntry entry(String actorId) {
        final TestEntry e = new TestEntry();
        e.id = UUID.randomUUID();
        e.subjectId = UUID.randomUUID();
        e.sequenceNumber = 1;
        e.entryType = LedgerEntryType.EVENT;
        e.actorId = actorId;
        e.actorType = ActorType.AGENT;
        e.actorRole = "Classifier";
        e.occurredAt = Instant.parse("2026-01-15T12:00:00Z");
        e.previousHash = null;
        e.digest = "abc123";
        return e;
    }

    @Test
    void toJson_coreFields_allPresent() {
        final TestEntry e = entry("agent-1");
        final String json = LedgerEntryArchiver.toJson(e, List.of());

        assertThat(json).contains("\"id\":\"" + e.id + "\"");
        assertThat(json).contains("\"subjectId\":\"" + e.subjectId + "\"");
        assertThat(json).contains("\"sequenceNumber\":1");
        assertThat(json).contains("\"entryType\":\"EVENT\"");
        assertThat(json).contains("\"actorId\":\"agent-1\"");
        assertThat(json).contains("\"actorType\":\"AGENT\"");
        assertThat(json).contains("\"actorRole\":\"Classifier\"");
        assertThat(json).contains("\"occurredAt\":\"2026-01-15T12:00:00Z\"");
        assertThat(json).contains("\"digest\":\"abc123\"");
    }

    @Test
    void toJson_nullFieldsOmitted() {
        final TestEntry e = entry("agent-1");
        e.previousHash = null;
        e.supplementJson = null;

        final String json = LedgerEntryArchiver.toJson(e, List.of());

        assertThat(json).doesNotContain("previousHash");
        assertThat(json).doesNotContain("supplementJson");
    }

    @Test
    void toJson_supplementJson_included() {
        final TestEntry e = entry("agent-1");
        e.supplementJson = "{\"COMPLIANCE\":{\"algorithmRef\":\"v1\"}}";

        final String json = LedgerEntryArchiver.toJson(e, List.of());

        assertThat(json).contains("supplementJson");
        assertThat(json).contains("algorithmRef");
    }

    @Test
    void toJson_noAttestations_emptyArrayOmitted() {
        final TestEntry e = entry("agent-1");
        final String json = LedgerEntryArchiver.toJson(e, List.of());

        assertThat(json).doesNotContain("attestations");
    }

    @Test
    void toJson_withAttestations_includedInOutput() {
        final TestEntry e = entry("agent-1");
        final LedgerAttestation att = new LedgerAttestation();
        att.id = UUID.randomUUID();
        att.ledgerEntryId = e.id;
        att.attestorId = "compliance-bot";
        att.attestorType = ActorType.AGENT;
        att.verdict = AttestationVerdict.SOUND;
        att.confidence = 0.95;
        att.occurredAt = Instant.parse("2026-01-15T12:01:00Z");

        final String json = LedgerEntryArchiver.toJson(e, List.of(att));

        assertThat(json).contains("\"attestations\"");
        assertThat(json).contains("\"attestorId\":\"compliance-bot\"");
        assertThat(json).contains("\"verdict\":\"SOUND\"");
        assertThat(json).contains("\"confidence\":0.95");
    }

    @Test
    void toJson_isValidJson_parseable() throws Exception {
        final TestEntry e = entry("agent-1");
        e.supplementJson = "{\"COMPLIANCE\":{\"planRef\":\"policy-v1\"}}";
        final String json = LedgerEntryArchiver.toJson(e, List.of());

        // Should not throw
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
    }
}
