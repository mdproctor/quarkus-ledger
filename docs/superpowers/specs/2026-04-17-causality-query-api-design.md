# Design Spec — Causality Query API

**Date:** 2026-04-17
**Issue:** #10 — Causality query API — findCausedBy for cross-system causal chain traversal
**Status:** Approved

---

## Problem

`ObservabilitySupplement.causedByEntryId` exists for consumers to record which entry
causally triggered another. But it is in the wrong place and has no query support:

1. **Wrong abstraction:** Causality is a fundamental temporal relationship between audit
   records — as structural as `sequenceNumber` or `occurredAt`. It is not optional
   enrichment. Moving it to a supplement buried the field behind a join and made querying
   impossible without traversing supplement tables.

2. **No query API:** Even if the field is set, there is no way to ask "what did this
   entry trigger?" without writing custom Panache queries.

Together these mean the causal chain across Tarkus → Qhorus → Claudony is invisible
in the audit record even when consumers record it. Axiom 3 (Temporal Coherence) in
`docs/AUDITABILITY.md` remains ⚠️ Partial.

---

## Design Constraint

No new migration — all changes go into existing V1000 and V1002 migration files.
We are pre-release with no deployed data. Editing existing migrations is cleaner
than ALTER TABLE chains.

---

## Changes

### 1. `V1000__ledger_base_schema.sql` — add column + index

Add to the `CREATE TABLE ledger_entry` column list:

```sql
    caused_by_entry_id   UUID,
```

Add to the index block after the table:

```sql
CREATE INDEX idx_ledger_entry_caused_by ON ledger_entry (caused_by_entry_id);
```

### 2. `V1002__ledger_supplement.sql` — remove from supplement

In `CREATE TABLE ledger_supplement_observability`, remove the line:

```sql
    caused_by_entry_id UUID,
```

### 3. `LedgerEntry.java` — add core field

```java
/**
 * FK to the ledger entry that causally produced this entry.
 * Null for entries with no known causal predecessor.
 *
 * <p>
 * When set, enables cross-system causal chain traversal via
 * {@link io.quarkiverse.ledger.runtime.repository.LedgerEntryRepository#findCausedBy(UUID)}.
 * Use this when an orchestrator (e.g. Claudony) triggers work in Tarkus which triggers
 * a message in Qhorus — each downstream entry's {@code causedByEntryId} points to the
 * upstream entry, enabling full causal reconstruction.
 *
 * <p>
 * Setting this field requires attaching an
 * {@link io.quarkiverse.ledger.runtime.model.supplement.ObservabilitySupplement}
 * is no longer necessary for causality — this is a core field.
 */
@Column(name = "caused_by_entry_id")
public UUID causedByEntryId;
```

### 4. `ObservabilitySupplement.java` — remove field

Remove `causedByEntryId` from the supplement. The supplement now carries only `correlationId`
(OTel trace linking — genuinely optional enrichment, kept as a supplement).

Update class Javadoc to reflect: supplement now covers OTel correlation only; causality
is recorded via `LedgerEntry.causedByEntryId`.

### 5. `LedgerEntryRepository.java` — new SPI method

```java
/**
 * Return all ledger entries that were causally triggered by the given entry,
 * ordered by {@code occurredAt} ascending.
 *
 * <p>
 * Returns direct effects only (one hop). For recursive traversal of a full
 * causal chain, call iteratively: {@code findCausedBy(findCausedBy(rootId).get(0).id)}.
 *
 * @param entryId the entry whose effects to retrieve
 * @return ordered list of entries with {@code causedByEntryId == entryId}; empty if none
 */
List<LedgerEntry> findCausedBy(UUID entryId);
```

### 6. `JpaLedgerEntryRepository.java` — implementation

```java
@Override
public List<LedgerEntry> findCausedBy(final UUID entryId) {
    return LedgerEntry.list(
            "causedByEntryId = ?1 ORDER BY occurredAt ASC", entryId);
}
```

### 7. `LedgerSupplementSerializer.java` — remove causedByEntryId

Remove `causedByEntryId` from the `ObservabilitySupplement` serialisation block.
The field is now on the core entity and will be in `supplementJson` no longer —
it will appear in the regular entity JSON and in `LedgerEntryArchiver` output.

### 8. `LedgerEntryArchiver.java` — add causedByEntryId to archive JSON

Add to the core field block:

```java
if (entry.causedByEntryId != null)
    map.put("causedByEntryId", entry.causedByEntryId.toString());
```

---

## Testing

### Unit tests
None needed for `findCausedBy` itself — it is a trivial Panache one-liner verified
by integration tests.

Updated `LedgerSupplementSerializerTest` — confirm `causedByEntryId` no longer appears
in `ObservabilitySupplement` JSON output.

Updated `LedgerEntryArchiverTest` — confirm `causedByEntryId` appears in archive JSON
when set on a core entry.

### Integration tests (`@QuarkusTest`)

New `CausalityQueryIT`:

| Test | What it proves |
|---|---|
| `findCausedBy_rootEntry_returnsDirectEffects` | Entry A triggers B and C; `findCausedBy(A)` returns B and C, not A itself |
| `findCausedBy_midChain_returnsOneHop` | Entry B causes C; `findCausedBy(B)` returns C only |
| `findCausedBy_leaf_returnsEmpty` | Entry C causes nothing; `findCausedBy(C)` is empty |
| `findCausedBy_noCausalLinks_returnsEmpty` | Entries with no `causedByEntryId` set |
| `findCausedBy_orderedByOccurredAtAsc` | Two effects of same cause ordered correctly |

---

## Zero-Complexity Verification

| Scenario | Behaviour |
|---|---|
| Consumer never sets `causedByEntryId` | Field is null — identical to current behaviour |
| Consumer calls `findCausedBy(id)` on entry with no effects | Returns empty list |
| Existing `ObservabilitySupplement` usage with `correlationId` only | No change needed |

---

## AUDITABILITY.md Update

Axiom 3 (Temporal Coherence) summary row → `✅ Addressed (#10)`.
Axiom 3 body section → add "Addressed by: `causedByEntryId` on core `LedgerEntry`,
`findCausedBy()` SPI method. One-hop; recursive traversal is application-level."

---

## References

- Issue #10
- `docs/AUDITABILITY.md` — Axiom 3 (Temporal Coherence)
- `docs/RESEARCH.md` — priority matrix item #5
