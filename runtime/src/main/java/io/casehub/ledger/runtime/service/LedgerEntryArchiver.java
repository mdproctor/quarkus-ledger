package io.casehub.ledger.runtime.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.casehub.ledger.runtime.model.LedgerAttestation;
import io.casehub.ledger.runtime.model.LedgerEntry;

/**
 * Serialises a {@link LedgerEntry} and its attestations to a stable JSON string
 * for storage in {@code ledger_entry_archive.entry_json}.
 *
 * <p>
 * The JSON is self-contained: all core fields, {@code supplementJson}, and all
 * attestations are included. Null fields are omitted. The format is additive —
 * future fields added to {@link LedgerEntry} do not invalidate existing records.
 *
 * <p>
 * Pure static utility — no CDI, no database. Safe to use in unit tests.
 */
public final class LedgerEntryArchiver {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LedgerEntryArchiver() {
    }

    /**
     * Serialise a ledger entry and its attestations to a JSON string for archival.
     *
     * @param entry the entry to serialise; must not be null
     * @param attestations the attestations for this entry; may be null or empty
     * @return a non-null JSON string
     */
    public static String toJson(final LedgerEntry entry,
            final List<LedgerAttestation> attestations) {
        final Map<String, Object> map = new LinkedHashMap<>();

        map.put("id", entry.id.toString());
        map.put("subjectId", entry.subjectId.toString());
        map.put("sequenceNumber", entry.sequenceNumber);
        if (entry.entryType != null)
            map.put("entryType", entry.entryType.name());
        if (entry.actorId != null)
            map.put("actorId", entry.actorId);
        if (entry.actorType != null)
            map.put("actorType", entry.actorType.name());
        if (entry.actorRole != null)
            map.put("actorRole", entry.actorRole);
        if (entry.occurredAt != null)
            map.put("occurredAt", entry.occurredAt.toString());
        if (entry.digest != null)
            map.put("digest", entry.digest);
        if (entry.traceId != null)
            map.put("traceId", entry.traceId);
        if (entry.causedByEntryId != null)
            map.put("causedByEntryId", entry.causedByEntryId.toString());
        if (entry.supplementJson != null)
            map.put("supplementJson", entry.supplementJson);

        if (attestations != null && !attestations.isEmpty()) {
            final List<Map<String, Object>> attList = attestations.stream()
                    .map(LedgerEntryArchiver::attestationToMap)
                    .toList();
            map.put("attestations", attList);
        }

        try {
            return MAPPER.writeValueAsString(map);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise entry to JSON for archive", e);
        }
    }

    private static Map<String, Object> attestationToMap(final LedgerAttestation a) {
        final Map<String, Object> m = new LinkedHashMap<>();
        if (a.id != null)
            m.put("id", a.id.toString());
        if (a.attestorId != null)
            m.put("attestorId", a.attestorId);
        if (a.attestorType != null)
            m.put("attestorType", a.attestorType.name());
        if (a.attestorRole != null)
            m.put("attestorRole", a.attestorRole);
        if (a.verdict != null)
            m.put("verdict", a.verdict.name());
        m.put("confidence", a.confidence);
        if (a.occurredAt != null)
            m.put("occurredAt", a.occurredAt.toString());
        return m;
    }
}
