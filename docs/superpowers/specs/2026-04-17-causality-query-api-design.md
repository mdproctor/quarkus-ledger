# Design Spec — Causality & Observability to Core + findCausedBy Query

**Date:** 2026-04-17
**Issue:** #10 — Causality query API — findCausedBy for cross-system causal chain traversal
**Status:** Approved

---

## Problem

Two fields currently in `ObservabilitySupplement` should be core fields on `LedgerEntry`,
not optional enrichment:

- **`causedByEntryId`** — which entry causally triggered this one. Causality is a structural
  temporal relationship between audit records, as fundamental as `sequenceNumber` or
  `occurredAt`. It is not optional enrichment.

- **`correlationId`** — which OTel distributed trace triggered this entry. OTel is
  built-in to Quarkus; every REST call and agent invocation in the ecosystem has a trace
  ID. This is a universal structural link between audit and observability layers.

With both fields moved, `ObservabilitySupplement` has no fields left. The correct
conclusion is to remove it entirely — the supplement abstraction is correct for
`ComplianceSupplement` (GDPR Art.22 — only regulated AI decisions) and
`ProvenanceSupplement` (workflow origin — only externally-orchestrated consumers),
but was misapplied to structural relationships.

Additionally: no query API exists to ask "what entries did this entry trigger?" — closing
Axiom 3 (Temporal Coherence) requires both moving the field and exposing the traversal.

---

## Design Constraint

No new migration — all changes to schema go into existing V1000 and V1002 files.
We are pre-release with no deployed data. Editing existing migrations is correct.

Flyway checksum will change on V1000 and V1002. Consumers running from H2 in-memory
(all current test setups) start fresh each run — no impact. Document: `mvn clean test`
required after pulling these changes.

---

## Changes

### 1. V1000__ledger_base_schema.sql

Add two columns to `CREATE TABLE ledger_entry`:
```sql
    caused_by_entry_id   UUID,
    correlation_id       VARCHAR(255),
```

Add two indexes:
```sql
CREATE INDEX idx_ledger_entry_caused_by  ON ledger_entry (caused_by_entry_id);
CREATE INDEX idx_ledger_entry_correlation ON ledger_entry (correlation_id);
```

Note: `idx_ledger_entry_correlation` already exists in V1000 (it was there before V1002
moved `correlation_id` to supplement). Keep it.

### 2. V1002__ledger_supplement.sql

Remove `CREATE TABLE ledger_supplement_observability` entirely.

Remove the `ALTER TABLE ledger_entry DROP COLUMN correlation_id` statement
(since `correlation_id` stays on the core table, V1002 must not drop it).

Remove the `ALTER TABLE ledger_entry DROP COLUMN caused_by_entry_id` statement
(same reason).

### 3. LedgerEntry.java — add two core fields

```java
/**
 * OpenTelemetry trace ID linking this entry to a distributed trace.
 * Use the W3C trace context format (32-char hex string).
 * Set from the active span context at capture time.
 */
@Column(name = "correlation_id", length = 255)
public String correlationId;

/**
 * FK to the ledger entry that causally produced this entry.
 * Null for entries with no known causal predecessor.
 *
 * <p>
 * When set, enables cross-system causal chain traversal via
 * {@link io.casehub.ledger.runtime.repository.LedgerEntryRepository#findCausedBy(UUID)}.
 * Use this when an orchestrator (e.g. Claudony) triggers work in Tarkus which triggers
 * a message in Qhorus — each downstream entry points back to its upstream cause.
 */
@Column(name = "caused_by_entry_id")
public UUID causedByEntryId;
```

### 4. ObservabilitySupplement.java — delete entirely

Remove the file. No fields remain after moving `correlationId` and `causedByEntryId`
to core.

### 5. LedgerEntry.java — remove observability() accessor

Remove the `observability()` method. Remove the import of `ObservabilitySupplement`.
Remove OBSERVABILITY from any related javadoc.

### 6. SupplementType.java (if it exists) — remove OBSERVABILITY

Or if `SupplementType` is an enum, it may not exist as a standalone file — the
supplement type is derived from `instanceof` checks in `LedgerSupplementSerializer`.
Remove the `ObservabilitySupplement` branch from the serializer.

### 7. LedgerSupplementSerializer.java — remove OBSERVABILITY branch

Remove `if (supplement instanceof ObservabilitySupplement)` block from `typeKey()`
and `toFieldMap()`. Remove import. The serializer now handles only COMPLIANCE and
PROVENANCE.

### 8. LedgerEntryArchiver.java — add correlationId + causedByEntryId to core block

```java
if (entry.correlationId != null)      map.put("correlationId", entry.correlationId);
if (entry.causedByEntryId != null)    map.put("causedByEntryId", entry.causedByEntryId.toString());
```

### 9. LedgerEntryRepository.java — new SPI method

```java
/**
 * Return all ledger entries that were causally triggered by the given entry,
 * ordered by {@code occurredAt} ascending. Returns direct effects only (one hop).
 *
 * @param entryId the entry whose effects to retrieve
 * @return ordered list of entries with {@code causedByEntryId == entryId}; empty if none
 */
List<LedgerEntry> findCausedBy(UUID entryId);
```

### 10. JpaLedgerEntryRepository.java — implement findCausedBy

```java
@Override
public List<LedgerEntry> findCausedBy(final UUID entryId) {
    return LedgerEntry.list("causedByEntryId = ?1 ORDER BY occurredAt ASC", entryId);
}
```

### 11. Consumer repositories — implement findCausedBy

Any class implementing `LedgerEntryRepository` in the examples must add the method.
Currently: `OrderLedgerEntryRepository` in `examples/order-processing`.

### 12. AUDITABILITY.md

Axiom 3 (Temporal Coherence): status → `✅ Addressed (#10)`.

---

## Testing Strategy

### Unit tests

**`LedgerSupplementSerializerTest`** — remove the two `observabilitySupplement_*` tests.
Add: `toJson_withObservabilitySupplement_notSerialised` → confirm
`new ObservabilitySupplement()` throws `IllegalArgumentException` from `typeKey()`.
Wait — `ObservabilitySupplement` is deleted. Update tests to only cover
COMPLIANCE and PROVENANCE. Remove any OBSERVABILITY test.

**`LedgerEntryArchiverTest`** — add two tests:
- `toJson_correlationId_included` — entry with `correlationId` set → appears in JSON
- `toJson_causedByEntryId_included` — entry with `causedByEntryId` set → appears in JSON

### Integration tests (`@QuarkusTest`)

**`LedgerSupplementIT`** — remove `observabilitySupplement_persistsAndLoads` test
and the OBSERVABILITY key assertions from `supplementJson_containsAllAttachedSupplements`.

**New `CausalityQueryIT`** (happy path + edge cases):

| Test | What it proves |
|---|---|
| `findCausedBy_rootEntry_returnsDirectEffects` | Entry A → B, C; `findCausedBy(A)` returns B and C |
| `findCausedBy_midChain_returnsOneHop` | B → C; `findCausedBy(B)` returns only C |
| `findCausedBy_leaf_returnsEmpty` | C causes nothing; `findCausedBy(C)` is empty |
| `findCausedBy_noLinks_returnsEmpty` | Entries with null `causedByEntryId` |
| `findCausedBy_orderedByOccurredAtAsc` | Two effects of same cause ordered correctly |
| `correlationId_persistsAndQueryable` | `correlationId` set on core entry, persists, returned in `findByActorId` response |

### End-to-end / happy path

Extend `examples/order-processing/OrderLedgerIT`:
- Add one test that sets `correlationId` on an order entry and verifies it appears in the ledger response

Extend `CausalityQueryIT` with an orchestration scenario:
- Seed three entries mimicking Claudony → Tarkus → Qhorus (A causes B causes C)
- Verify full two-hop traversal via two `findCausedBy` calls
- This is the canonical happy path for the feature

---

## Zero-Complexity Verification

| Scenario | Behaviour |
|---|---|
| Consumer never sets `causedByEntryId` | Field is null — identical to current |
| Consumer never sets `correlationId` | Field is null — identical to current |
| Consumer was using `ObservabilitySupplement` | Must migrate: set `entry.correlationId` and `entry.causedByEntryId` directly |
| Consumer uses only `ComplianceSupplement` | Zero change |
| Consumer uses only `ProvenanceSupplement` | Zero change |

---

## References

- Issue #10
- `docs/AUDITABILITY.md` — Axiom 3 (Temporal Coherence)
- `docs/RESEARCH.md` — priority matrix item #5
- `adr/0001-forgiveness-mechanism-omits-severity-dimension.md` — ADR pattern for decisions made
