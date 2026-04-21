# W3C PROV-DM Mapping Reference

Documents how `quarkus-ledger` fields map to W3C PROV-DM concepts in the JSON-LD
export produced by `LedgerProvExportService.exportSubject(UUID)`.

## PROV-DM Primer

PROV-DM has three core types:

| PROV type | Meaning | Ledger mapping |
|---|---|---|
| `prov:Entity` | A thing with a provenance story | Each `LedgerEntry` |
| `prov:Agent` | An actor who caused something | Each distinct `actorId` |
| `prov:Activity` | An action that occurred | One per `LedgerEntry` |

And three core relations:

| Relation | Meaning | Emitted when |
|---|---|---|
| `wasGeneratedBy` | An entity was produced by an activity | Every entry |
| `wasAssociatedWith` | An activity was performed by an agent | `actorId` is non-null |
| `wasDerivedFrom` | An entity derives from another entity | Sequential chain + `causedByEntryId` |

## IRI Conventions

All IRIs use the `ledger:` prefix (`http://quarkiverse.io/ledger#`):

| Element | IRI pattern | Example |
|---|---|---|
| Entry entity | `ledger:entry/<uuid>` | `ledger:entry/e1a2b3...` |
| Agent (human/system) | `ledger:actor/<actorId>` | `ledger:actor/alice` |
| Agent (LLM) | `ledger:actor/<actorId>` | `ledger:actor/claude:tarkus-reviewer@v1` |
| Activity | `ledger:activity/<uuid>` | `ledger:activity/e1a2b3...` |
| External source | `ledger:external/<type>/<system>/<id>` | `ledger:external/WorkItem/tarkus/wi-abc` |

Agents are **deduplicated** — the same `actorId` appearing across multiple entries
produces a single `agent` node in the document.

**LLM agent IRIs:** For LLM agents, `actorId` follows the versioned persona convention
`{model-family}:{persona}@{major}` (ADR 0004). The colon and `@` are valid IRI
characters; the resulting IRI `ledger:actor/claude:tarkus-reviewer@v1` is unambiguous
and stable across sessions.

## Core LedgerEntry Fields

| `LedgerEntry` field | PROV-DM location | Property |
|---|---|---|
| `id` | Entity IRI | `ledger:entry/<id>` |
| `subjectId` | Entity property | `ledger:subjectId` |
| `sequenceNumber` | Entity property | `ledger:sequenceNumber` |
| `entryType` | Entity + Activity property | `ledger:entryType` |
| `digest` | Entity property | `ledger:digest` |
| `occurredAt` | Entity + Activity property | `prov:generatedAtTime`, `prov:startedAtTime` |
| `correlationId` | Entity property | `ledger:correlationId` |
| `actorId` | Agent IRI | `ledger:actor/<actorId>` |
| `actorType` | Agent property | `ledger:actorType` |
| `actorRole` | Agent property | `ledger:actorRole` |
| `causedByEntryId` | `wasDerivedFrom` relation | Cross-subject causal link |

## ComplianceSupplement Fields

Compliance fields appear as additional properties on the `prov:Entity` when a
`ComplianceSupplement` is attached to the entry. Null fields are omitted.

| `ComplianceSupplement` field | Entity property | Notes |
|---|---|---|
| `algorithmRef` | `ledger:algorithmRef` | Model or rule engine version (GDPR Art.22) |
| `confidenceScore` | `ledger:confidenceScore` | 0.0–1.0 producing system confidence |
| `contestationUri` | `ledger:contestationUri` | URI for challenging the decision |
| `humanOverrideAvailable` | `ledger:humanOverrideAvailable` | Boolean |
| `planRef` | `ledger:planRef` | Policy / procedure version reference |
| `rationale` | `ledger:rationale` | Actor's stated basis for the decision |
| `evidence` | `ledger:evidence` | Structured evidence (JSON string) |
| `decisionContext` | `ledger:decisionContext` | JSON snapshot of decision-time state |

## ProvenanceSupplement Fields

A `ProvenanceSupplement` produces a `hadPrimarySource` relation from the entry entity
to an external IRI constructed from the three source fields.

| `ProvenanceSupplement` field | Role in IRI / export |
|---|---|
| `sourceEntityType` | Type segment: `ledger:external/<type>/...` |
| `sourceEntitySystem` | System segment: `.../tarkus/...` |
| `sourceEntityId` | ID segment: `.../wi-abc` |
| `agentConfigHash` | Not mapped to PROV-DM — forensic audit field only; emitted as `ledger:agentConfigHash` on the Agent node when non-null |

Example: `sourceEntityType=WorkItem`, `sourceEntitySystem=tarkus`, `sourceEntityId=wi-abc`
→ `ledger:external/WorkItem/tarkus/wi-abc`

## wasDerivedFrom Relations

Two situations produce a `wasDerivedFrom` edge:

1. **Sequential chain** — every entry with `sequenceNumber > 1` derives from its predecessor.
   Key: `_:wdf-<entry-uuid>-<prev-uuid>`

2. **Cross-subject causality** — when `causedByEntryId` is set, the entry derives from
   the causal predecessor (which may be in a different subject's history).
   Key: `_:wdf-caused-<entry-uuid>-<caused-by-uuid>`

## @context

The JSON-LD `@context` is always exactly:

```json
{
  "@context": {
    "prov": "http://www.w3.org/ns/prov#",
    "ledger": "http://quarkiverse.io/ledger#",
    "xsd": "http://www.w3.org/2001/XMLSchema#"
  }
}
```
