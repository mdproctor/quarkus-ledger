package io.quarkiverse.ledger.runtime.model.supplement;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Serialises a list of {@link LedgerSupplement} instances to a compact JSON string
 * for storage in the {@code supplement_json} column of {@code ledger_entry}.
 *
 * <p>
 * Each supplement is serialised under its type key ({@code "COMPLIANCE"},
 * {@code "PROVENANCE"}, {@code "OBSERVABILITY"}). Null fields are omitted.
 * Returns {@code null} when the list is null or empty — preserving a null
 * {@code supplement_json} for entries that carry no supplements.
 *
 * <p>
 * This class is not a CDI bean — it is a pure static utility with no Quarkus
 * runtime dependency. It can be used in unit tests without a running container.
 */
public final class LedgerSupplementSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LedgerSupplementSerializer() {
    }

    /**
     * Serialise a list of supplements to a JSON string.
     *
     * @param supplements the supplements to serialise; may be null or empty
     * @return a JSON string, or {@code null} if the list is null or empty
     */
    public static String toJson(final List<LedgerSupplement> supplements) {
        if (supplements == null || supplements.isEmpty()) {
            return null;
        }
        final Map<String, Object> root = new LinkedHashMap<>();
        for (final LedgerSupplement supplement : supplements) {
            final Map<String, Object> fields = toFieldMap(supplement);
            if (!fields.isEmpty()) {
                root.put(typeKey(supplement), fields);
            }
        }
        if (root.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise supplements to JSON", e);
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private static String typeKey(final LedgerSupplement supplement) {
        if (supplement instanceof ComplianceSupplement) {
            return "COMPLIANCE";
        }
        if (supplement instanceof ProvenanceSupplement) {
            return "PROVENANCE";
        }
        if (supplement instanceof ObservabilitySupplement) {
            return "OBSERVABILITY";
        }
        throw new IllegalArgumentException("Unknown supplement type: " + supplement.getClass().getName());
    }

    private static Map<String, Object> toFieldMap(final LedgerSupplement supplement) {
        final Map<String, Object> map = new LinkedHashMap<>();
        if (supplement instanceof final ComplianceSupplement c) {
            putIfNotNull(map, "planRef", c.planRef);
            putIfNotNull(map, "rationale", c.rationale);
            putIfNotNull(map, "evidence", c.evidence);
            putIfNotNull(map, "detail", c.detail);
            putIfNotNull(map, "decisionContext", c.decisionContext);
            putIfNotNull(map, "algorithmRef", c.algorithmRef);
            putIfNotNull(map, "confidenceScore", c.confidenceScore);
            putIfNotNull(map, "contestationUri", c.contestationUri);
            putIfNotNull(map, "humanOverrideAvailable", c.humanOverrideAvailable);
        } else if (supplement instanceof final ProvenanceSupplement p) {
            putIfNotNull(map, "sourceEntityId", p.sourceEntityId);
            putIfNotNull(map, "sourceEntityType", p.sourceEntityType);
            putIfNotNull(map, "sourceEntitySystem", p.sourceEntitySystem);
        } else if (supplement instanceof final ObservabilitySupplement o) {
            putIfNotNull(map, "correlationId", o.correlationId);
            if (o.causedByEntryId != null) {
                map.put("causedByEntryId", o.causedByEntryId.toString());
            }
        }
        return map;
    }

    private static void putIfNotNull(final Map<String, Object> map, final String key, final Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
