package io.casehub.ledger.runtime.service;

import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.casehub.ledger.runtime.model.LedgerEntry;

/**
 * Serialises a subject's ledger history as a W3C PROV-DM JSON-LD document.
 *
 * <p>
 * Mapping: {@code LedgerEntry} → {@code prov:Entity}, {@code actorId} → {@code prov:Agent}
 * (deduplicated), entry action → {@code prov:Activity}. See {@code docs/prov-dm-mapping.md}
 * for the full field-by-field reference.
 *
 * <p>
 * Pure static utility — no CDI, no DB access. Call from within a {@code @Transactional}
 * context so supplement lazy-loading succeeds.
 */
public final class LedgerProvSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<String, Object> CONTEXT = Map.of(
            "prov", "http://www.w3.org/ns/prov#",
            "ledger", "https://casehubio.github.io/ledger#",
            "xsd", "http://www.w3.org/2001/XMLSchema#");

    private LedgerProvSerializer() {
    }

    /**
     * Serialise the entries for a subject as a PROV-JSON-LD document.
     *
     * @param subjectId the aggregate identifier scoping this bundle
     * @param entries ordered list (ascending sequenceNumber) for this subject
     * @return pretty-printed PROV-JSON-LD string
     */
    public static String toProvJsonLd(final UUID subjectId, final List<LedgerEntry> entries) {
        final Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("@context", CONTEXT);

        final Map<String, Object> entities = new LinkedHashMap<>();
        final Map<String, Object> agents = new LinkedHashMap<>();
        final Map<String, Object> activities = new LinkedHashMap<>();
        final Map<String, Object> wasGeneratedBy = new LinkedHashMap<>();
        final Map<String, Object> wasAssociatedWith = new LinkedHashMap<>();
        final Map<String, Object> wasDerivedFrom = new LinkedHashMap<>();
        final Map<String, Object> hadPrimarySource = new LinkedHashMap<>();

        final Map<Integer, LedgerEntry> bySeq = entries.stream()
                .collect(Collectors.toMap(e -> e.sequenceNumber, e -> e));

        for (final LedgerEntry entry : entries) {
            final String entryIri = entryIri(entry.id);
            final String activityIri = activityIri(entry.id);

            // Entity
            final Map<String, Object> entity = new LinkedHashMap<>();
            entity.put("prov:type", "ledger:LedgerEntry");
            entity.put("ledger:subjectId", subjectId.toString());
            entity.put("ledger:sequenceNumber", entry.sequenceNumber);
            entity.put("ledger:entryType", entry.entryType != null ? entry.entryType.name() : null);
            putIfNotNull(entity, "ledger:digest", entry.digest);
            entity.put("prov:generatedAtTime", formatInstant(entry.occurredAt));
            putIfNotNull(entity, "ledger:traceId", entry.traceId);

            entry.compliance().ifPresent(cs -> {
                putIfNotNull(entity, "ledger:algorithmRef", cs.algorithmRef);
                putIfNotNull(entity, "ledger:confidenceScore", cs.confidenceScore);
                putIfNotNull(entity, "ledger:contestationUri", cs.contestationUri);
                putIfNotNull(entity, "ledger:humanOverrideAvailable", cs.humanOverrideAvailable);
                putIfNotNull(entity, "ledger:planRef", cs.planRef);
                putIfNotNull(entity, "ledger:rationale", cs.rationale);
                putIfNotNull(entity, "ledger:evidence", cs.evidence);
                putIfNotNull(entity, "ledger:decisionContext", cs.decisionContext);
            });
            entities.put(entryIri, entity);

            // Activity
            final Map<String, Object> activity = new LinkedHashMap<>();
            activity.put("prov:type", "ledger:Activity");
            activity.put("ledger:entryType", entry.entryType != null ? entry.entryType.name() : null);
            activity.put("prov:startedAtTime", formatInstant(entry.occurredAt));
            activities.put(activityIri, activity);

            // Agent (deduplicated)
            if (entry.actorId != null && !agents.containsKey(agentIri(entry.actorId))) {
                final Map<String, Object> agent = new LinkedHashMap<>();
                agent.put("prov:type", "ledger:Actor");
                putIfNotNull(agent, "ledger:actorType",
                        entry.actorType != null ? entry.actorType.name() : null);
                putIfNotNull(agent, "ledger:actorRole", entry.actorRole);
                agents.put(agentIri(entry.actorId), agent);
            }

            // wasGeneratedBy
            final Map<String, Object> wgb = new LinkedHashMap<>();
            wgb.put("prov:entity", entryIri);
            wgb.put("prov:activity", activityIri);
            wasGeneratedBy.put("_:wgb-" + entry.id, wgb);

            // wasAssociatedWith
            if (entry.actorId != null) {
                final Map<String, Object> waw = new LinkedHashMap<>();
                waw.put("prov:activity", activityIri);
                waw.put("prov:agent", agentIri(entry.actorId));
                wasAssociatedWith.put("_:waw-" + entry.id, waw);
            }

            // wasDerivedFrom — sequential chain
            if (entry.sequenceNumber > 1) {
                final LedgerEntry prev = bySeq.get(entry.sequenceNumber - 1);
                if (prev != null) {
                    final Map<String, Object> wdf = new LinkedHashMap<>();
                    wdf.put("prov:generatedEntity", entryIri);
                    wdf.put("prov:usedEntity", entryIri(prev.id));
                    wasDerivedFrom.put("_:wdf-" + entry.id + "-" + prev.id, wdf);
                }
            }

            // wasDerivedFrom — cross-subject causality
            if (entry.causedByEntryId != null) {
                final Map<String, Object> wdf = new LinkedHashMap<>();
                wdf.put("prov:generatedEntity", entryIri);
                wdf.put("prov:usedEntity", entryIri(entry.causedByEntryId));
                wasDerivedFrom.put("_:wdf-caused-" + entry.id + "-" + entry.causedByEntryId, wdf);
            }

            // hadPrimarySource — ProvenanceSupplement
            entry.provenance().ifPresent(ps -> {
                if (ps.sourceEntityId != null) {
                    final Map<String, Object> hps = new LinkedHashMap<>();
                    hps.put("prov:entity", entryIri);
                    hps.put("prov:hadPrimarySource",
                            externalIri(ps.sourceEntityType, ps.sourceEntitySystem, ps.sourceEntityId));
                    hadPrimarySource.put("_:hps-" + entry.id, hps);
                }
            });
        }

        doc.put("entity", entities);
        if (!agents.isEmpty())
            doc.put("agent", agents);
        doc.put("activity", activities);
        doc.put("wasGeneratedBy", wasGeneratedBy);
        if (!wasAssociatedWith.isEmpty())
            doc.put("wasAssociatedWith", wasAssociatedWith);
        if (!wasDerivedFrom.isEmpty())
            doc.put("wasDerivedFrom", wasDerivedFrom);
        if (!hadPrimarySource.isEmpty())
            doc.put("hadPrimarySource", hadPrimarySource);

        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(doc);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise PROV-JSON-LD", e);
        }
    }

    private static String entryIri(final UUID id) {
        return "ledger:entry/" + id;
    }

    private static String agentIri(final String actorId) {
        return "ledger:actor/" + actorId;
    }

    private static String activityIri(final UUID id) {
        return "ledger:activity/" + id;
    }

    private static String externalIri(final String type, final String system, final String id) {
        return "ledger:external/" + type + "/" + system + "/" + id;
    }

    private static String formatInstant(final java.time.Instant instant) {
        if (instant == null)
            return null;
        return instant.truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private static void putIfNotNull(final Map<String, Object> map, final String key, final Object value) {
        if (value != null)
            map.put(key, value);
    }
}
