# Example: W3C PROV-DM JSON-LD Export

This example demonstrates how to use `quarkus-ledger` with `LedgerProvExportService` to export
a subject's full audit trail as W3C PROV-DM JSON-LD — the standard interchange format for data
lineage and provenance.

## What is W3C PROV-DM?

PROV-DM is the W3C data model for provenance — the record of what entities existed, what
activities occurred, and who was responsible. It has three core primitives:

- **Entity** — a thing with a defined existence in time (a ledger entry, a decision, an artefact)
- **Activity** — something that happened over a period and used or generated entities
- **Agent** — something with agency (a person, software, or organisation) responsible for an activity

PROV-DM is used for data lineage in MLOps pipelines, regulatory evidence in financial services
(MiFID II, BCBS 239), and upstream-system tracing in distributed workflows. Because it is a
W3C standard, external tools — lineage dashboards, compliance platforms, audit registries —
can consume it without bespoke parsing.

## What is JSON-LD?

JSON-LD (JSON for Linked Data) serialises PROV-DM as plain JSON with a `@context` header that
maps each key to a globally unambiguous IRI. Any JSON parser can read it; any RDF tool can
reason over it. The `LedgerProvExportService` produces PROV-JSON-LD: valid JSON, valid PROV-DM,
and valid JSON-LD simultaneously.

## What this example shows

Three ledger entries are recorded for a single subject, demonstrating the full set of supplement
types and causal wiring:

| Entry | Type | Supplement | Notes |
|---|---|---|---|
| Entry 1 | `COMMAND` | `ComplianceSupplement` | AI classification decision — `algorithmRef`, `confidenceScore`, `contestationUri`, `planRef`, `rationale`, `decisionContext` |
| Entry 2 | `EVENT` | `ProvenanceSupplement` | Orchestrator event from upstream WorkItem — `sourceEntityId`, `sourceEntityType`, `sourceEntitySystem` |
| Entry 3 | `EVENT` | — | Caused by Entry 1 via `causedByEntryId` — appears as a `wasDerivedFrom` edge in the PROV graph |

The call to `exportService.exportSubject(subjectId)` collects all three entries and renders them
as a single PROV-JSON-LD document. Provenance supplements become `wasDerivedFrom` / `used`
edges; compliance supplements become entity attributes; causal links become explicit derivation
edges.

## Key pattern

```java
// Entry with both a ComplianceSupplement and causal wiring
ProvAuditEntry e1 = new ProvAuditEntry();
e1.subjectId = subjectId;
e1.entryType = LedgerEntryType.COMMAND;
e1.actorId   = "classifier-agent-v2";

ComplianceSupplement cs = new ComplianceSupplement();
cs.algorithmRef          = "gpt-4o";
cs.confidenceScore       = 0.94;
cs.contestationUri       = "https://example.com/challenge/" + subjectId;
cs.humanOverrideAvailable = true;
e1.attach(cs);
e1 = (ProvAuditEntry) repo.save(e1);

// Entry 3 caused by Entry 1 — appears as wasDerivedFrom in the PROV graph
ProvAuditEntry e3 = new ProvAuditEntry();
e3.causedByEntryId = e1.id;
repo.save(e3);

// Export the full subject as PROV-DM JSON-LD
String jsonLd = exportService.exportSubject(subjectId);
```

## Running the example

```bash
cd examples/prov-dm-export
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn quarkus:dev
```

## Running the tests

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test
```
