# W3C PROV-DM JSON-LD Export — Design Spec

**Date:** 2026-04-18
**Closes:** RESEARCH.md item #7
**Approach:** Approach B — static serialiser + CDI service

---

## Goal

Export any subject's complete audit trail as a W3C PROV-DM JSON-LD document, enabling
external interoperability with ML pipeline auditing tools, regulatory export, and RDF stores.

---

## PROV-DM Mapping

### Core types

| Ledger concept | PROV-DM type | IRI pattern |
|---|---|---|
| `LedgerEntry` | `prov:Entity` | `ledger:entry/<uuid>` |
| `actorId` | `prov:Agent` | `ledger:actor/<actorId>` (deduplicated) |
| Entry action | `prov:Activity` | `ledger:activity/<uuid>` (one per entry) |

### Relations

| Relation | Emitted when |
|---|---|
| `wasGeneratedBy(entry_entity, activity)` | Always — every entry |
| `wasAssociatedWith(activity, agent)` | When `actorId` is non-null |
| `wasDerivedFrom(entry_n, entry_{n-1})` | Sequential chain — every entry with seqNum > 1 |
| `wasDerivedFrom(entry, causedByEntry)` | When `causedByEntryId` is set (cross-subject causality) |
| `hadPrimarySource(entry, external_entity)` | When `ProvenanceSupplement` is attached |

### Supplement field mapping

**ComplianceSupplement** → additional properties on the `prov:Entity`:

| Supplement field | PROV-DM property |
|---|---|
| `algorithmRef` | `ledger:algorithmRef` |
| `confidenceScore` | `ledger:confidenceScore` |
| `contestationUri` | `ledger:contestationUri` |
| `humanOverrideAvailable` | `ledger:humanOverrideAvailable` |
| `planRef` | `ledger:planRef` |
| `rationale` | `ledger:rationale` |
| `evidence` | `ledger:evidence` |
| `decisionContext` | `ledger:decisionContext` |

**ProvenanceSupplement** → `hadPrimarySource` relation:

```
prov:hadPrimarySource(
  entity  = ledger:entry/<uuid>,
  source  = ledger:external/<sourceEntityType>/<sourceEntitySystem>/<sourceEntityId>
)
```

Null supplement fields are **omitted entirely** — no null noise in the output.

---

## JSON-LD Structure

```json
{
  "@context": {
    "prov": "http://www.w3.org/ns/prov#",
    "ledger": "https://casehubio.github.io/ledger#",
    "xsd": "http://www.w3.org/2001/XMLSchema#"
  },
  "entity": {
    "ledger:entry/<uuid>": {
      "prov:type": "ledger:LedgerEntry",
      "ledger:subjectId": "<uuid>",
      "ledger:sequenceNumber": 1,
      "ledger:entryType": "COMMAND",
      "ledger:digest": "<64-char hex>",
      "prov:generatedAtTime": "2026-04-18T10:00:00.000Z",
      "ledger:correlationId": "<otel-trace-id>",
      "ledger:algorithmRef": "gpt-4o",
      "ledger:confidenceScore": 0.92
    }
  },
  "agent": {
    "ledger:actor/<actorId>": {
      "prov:type": "ledger:Actor",
      "ledger:actorType": "AGENT",
      "ledger:actorRole": "Classifier"
    }
  },
  "activity": {
    "ledger:activity/<uuid>": {
      "prov:type": "ledger:Activity",
      "ledger:entryType": "COMMAND",
      "prov:startedAtTime": "2026-04-18T10:00:00.000Z"
    }
  },
  "wasGeneratedBy": {
    "_:wgb-<uuid>": {
      "prov:entity": "ledger:entry/<uuid>",
      "prov:activity": "ledger:activity/<uuid>"
    }
  },
  "wasAssociatedWith": {
    "_:waw-<uuid>": {
      "prov:activity": "ledger:activity/<uuid>",
      "prov:agent": "ledger:actor/<actorId>"
    }
  },
  "wasDerivedFrom": {
    "_:wdf-<uuid2>-<uuid1>": {
      "prov:generatedEntity": "ledger:entry/<uuid2>",
      "prov:usedEntity": "ledger:entry/<uuid1>"
    }
  },
  "hadPrimarySource": {
    "_:hps-<uuid>": {
      "prov:entity": "ledger:entry/<uuid>",
      "prov:hadPrimarySource": "ledger:external/<type>/<system>/<id>"
    }
  }
}
```

**Key decisions:**
- Agents are deduplicated — same `actorId` → one agent node regardless of how many entries
- `wasDerivedFrom` covers both sequential chain links AND `causedByEntryId` cross-subject links
- `entryType` appears on both entity and activity for query convenience
- `@context` keys are always exactly `prov`, `ledger`, `xsd`
- No null fields emitted

---

## Components

### `LedgerProvSerializer` (pure static)

```
runtime/src/main/java/io/quarkiverse/ledger/runtime/service/LedgerProvSerializer.java
```

```java
public final class LedgerProvSerializer {
    public static String toProvJsonLd(UUID subjectId, List<LedgerEntry> entries) { ... }
}
```

Builds the full JSON-LD document from memory — no DB access, no CDI. Uses
`Map<String, Object>` + Jackson `ObjectMapper` (already on Quarkus classpath).
Supplements are read from the lazy-loaded `entry.supplements` list.

### `LedgerProvExportService` (CDI)

```
runtime/src/main/java/io/quarkiverse/ledger/runtime/service/LedgerProvExportService.java
```

```java
@ApplicationScoped
public class LedgerProvExportService {
    @Transactional
    public String exportSubject(UUID subjectId) { ... }
}
```

Fetches all entries ordered by `sequenceNumber ASC`, initialises the lazy supplements list,
then delegates to `LedgerProvSerializer.toProvJsonLd()`.

### `docs/prov-dm-mapping.md`

Authoritative field-by-field reference explaining every supplement field's PROV-DM mapping,
IRI conventions, and worked example. Standalone — readable without the code.

### `examples/prov-dm-export/`

Runnable `@QuarkusTest` example covering:
- All three supplement types (ComplianceSupplement fully populated, ProvenanceSupplement)
- Cross-subject `causedByEntryId` link
- Sequential chain of 3 entries
- Assertion that output parses as valid JSON, contains `@context`, all major PROV-DM keys present

---

## Testing

### Unit tests — `LedgerProvSerializerTest` (no DB, no Quarkus)

- Single entry, no supplements → entity + agent + activity + wasGeneratedBy + wasAssociatedWith
- Two sequential entries → `wasDerivedFrom` entry2 → entry1
- `causedByEntryId` set → additional `wasDerivedFrom` emitted
- Same `actorId` across entries → one deduplicated agent node
- `ComplianceSupplement` fully populated → all `ledger:*` fields on entity, no nulls
- `ComplianceSupplement` with nulls → null fields omitted from output
- `ProvenanceSupplement` → `hadPrimarySource` with correct IRI
- Null `actorId` → no agent node, no `wasAssociatedWith`
- `@context` always present with exactly `prov`, `ledger`, `xsd` keys

### Integration tests — `LedgerProvExportServiceIT` (`@QuarkusTest`)

- 3 entries via `repo.save()` → `exportSubject()` → valid JSON, 3 entity nodes
- Entry with `ComplianceSupplement` → supplement fields in JSON
- Entry with `causedByEntryId` → `wasDerivedFrom` present

### Example IT — `ProvDmExportIT` (`examples/prov-dm-export/`)

- Happy path covering all supplement types → output parses as JSON, contains all PROV-DM keys
- `@context` has exactly `prov`, `ledger`, `xsd`

---

## What Is Not In Scope

- PROV-XML or PROV-N serialisations — JSON-LD only
- PROV validation (checking conformance to the W3C spec) — serialiser only
- Attestation export (`LedgerAttestation`) — out of scope for v1
- REST endpoint for export — consumers expose their own endpoints
