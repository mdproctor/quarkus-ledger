# Design Spec — EU AI Act Art.12 Compliance Surface

**Date:** 2026-04-17
**Issue:** #9 — EU AI Act Art.12 compliance surface — retention policy and audit query API
**Epic:** #6 — Agentic AI compliance and trust quality
**Status:** Approved

---

## Problem

EU AI Act Article 12 (Record-keeping) requires high-risk AI systems to automatically
record events, retain operational logs for at least 6 months, and ensure full
reconstructability of AI decisions on demand. Enforcement: 2 August 2026. Penalties:
up to €15M or 3% of global annual turnover.

The ledger stores the right data but lacks three things:

1. **Retention enforcement** — no mechanism to archive and remove entries past their
   retention window, nor to prove retention compliance to an auditor
2. **Audit query surface** — an auditor asking "show me everything actor X did between
   dates Y and Z" has no API to answer that without custom SQL
3. **Compliance documentation** — no document mapping specific Art.12 requirements to
   specific ledger capabilities for use in conformity assessments

---

## Design Constraint

> **If a consumer does not configure retention, nothing changes.** All three config keys
> default to values that produce zero behaviour change. The audit query methods are
> additive — existing `findBySubjectId` callers are unaffected.

---

## Part 1 — Archive Table and Migration

### Migration renaming (examples)

The base extension now reserves **V1000–V1003**. Consumer subclass join tables must
use **V1004+** (updated from V1003+). The two in-repo examples are renamed:

| Before | After | File |
|---|---|---|
| `V1003__order_ledger_entry.sql` | `V1004__order_ledger_entry.sql` | `examples/order-processing` |
| `V1003__decision_schema.sql` | `V1004__decision_schema.sql` | `examples/art22-decision-snapshot` |

### V1003 — `ledger_entry_archive` table

```sql
CREATE TABLE ledger_entry_archive (
    id                UUID        NOT NULL,
    original_entry_id UUID        NOT NULL,
    subject_id        UUID        NOT NULL,
    sequence_number   INT         NOT NULL,
    entry_json        TEXT        NOT NULL,
    entry_occurred_at TIMESTAMP   NOT NULL,
    archived_at       TIMESTAMP   NOT NULL,
    CONSTRAINT pk_ledger_entry_archive PRIMARY KEY (id)
);
CREATE INDEX idx_archive_subject  ON ledger_entry_archive (subject_id);
CREATE INDEX idx_archive_occurred ON ledger_entry_archive (entry_occurred_at);
```

`entry_json` holds a complete, self-contained JSON snapshot of the entry at archival
time — all core fields, `supplementJson`, and all attestations. It is reconstructable
without access to the original tables.

### `LedgerEntryArchiveRecord` entity

```java
@Entity
@Table(name = "ledger_entry_archive")
public class LedgerEntryArchiveRecord extends PanacheEntityBase {
    @Id public UUID id;
    @Column(name = "original_entry_id") public UUID originalEntryId;
    @Column(name = "subject_id")        public UUID subjectId;
    @Column(name = "sequence_number")   public int  sequenceNumber;
    @Column(columnDefinition = "TEXT")  public String entryJson;
    @Column(name = "entry_occurred_at") public Instant entryOccurredAt;
    @Column(name = "archived_at")       public Instant archivedAt;

    @PrePersist void prePersist() { if (id == null) id = UUID.randomUUID(); }
}
```

---

## Part 2 — `LedgerEntryArchiver` (pure Java, no CDI)

Static utility. Converts a `LedgerEntry` (with supplements loaded) and its attestations
into a stable JSON string for storage in `entry_json`.

```
{
  "id": "...",
  "subjectId": "...",
  "sequenceNumber": 1,
  "entryType": "EVENT",
  "actorId": "...",
  "actorType": "AGENT",
  "actorRole": "...",
  "occurredAt": "2026-01-15T12:00:00Z",
  "previousHash": "...",
  "digest": "...",
  "supplementJson": "{...}",
  "attestations": [
    {"id": "...", "attestorId": "...", "verdict": "SOUND", "confidence": 0.95, ...}
  ]
}
```

Null fields are omitted. The format is stable — adding new fields to `LedgerEntry` in
future versions does not invalidate existing archive records.

Uses Jackson (already an explicit dependency in `runtime/pom.xml`).

---

## Part 3 — `RetentionEligibilityChecker` (pure Java, no CDI)

Pure static class — no database access, no CDI. Takes a `Map<UUID, List<LedgerEntry>>`
(grouped by `subjectId`) and returns only subjects where **every entry** is older than
`operationalDays`.

```java
public static Map<UUID, List<LedgerEntry>> eligibleSubjects(
    Map<UUID, List<LedgerEntry>> allBySubject,
    Instant now,
    int operationalDays)
```

**All-or-nothing per subject chain:** if a subject has 5 entries older than the window
and 1 newer one, the entire subject is excluded. This preserves chain integrity — you
cannot archive the first half of a chain and leave the second half intact in the main
table with a dangling `previousHash`.

---

## Part 4 — `LedgerConfig.RetentionConfig`

New nested interface inside `LedgerConfig`:

```java
RetentionConfig retention();

interface RetentionConfig {
    @WithDefault("false") boolean enabled();
    @WithDefault("180")   int operationalDays();
    @WithDefault("true")  boolean archiveBeforeDelete();
}
```

| Key | Default | Description |
|---|---|---|
| `quarkus.ledger.retention.enabled` | `false` | Disabled by default — zero behaviour change |
| `quarkus.ledger.retention.operational-days` | `180` | EU AI Act Art.12 minimum (6 months) |
| `quarkus.ledger.retention.archive-before-delete` | `true` | Write to `ledger_entry_archive` before deleting |

---

## Part 5 — `LedgerRetentionJob`

`@ApplicationScoped`, `@Scheduled(every = "24h")`, gated by `retention.enabled`.

Per eligible subject, in a **single `@Transactional`**:

1. **Verify hash chain** — call `LedgerHashChain.verify(entries)`. If broken: log warning,
   skip subject entirely. Move to next subject.

2. **Archive** (if `archive-before-delete=true`) — for each entry:
   - Load attestations
   - Call `LedgerEntryArchiver.toJson(entry, attestations)` 
   - Persist a `LedgerEntryArchiveRecord`

3. **Delete attestations** — `LedgerAttestation.delete("ledgerEntryId in ?1", entryIds)`
   Must precede entry deletion — attestations reference `ledger_entry.id` via a non-cascaded
   FK constraint.

4. **Delete entries** — `entry.delete()` per entry (or bulk via Panache).
   Two distinct mechanisms handle the cascade:
   - **Supplements**: `LedgerEntry.supplements` has `@OneToMany(cascade=ALL, orphanRemoval=true)` —
     JPA deletes supplement subclass rows then `ledger_supplement` rows automatically.
   - **Subclass rows** (e.g. `order_ledger_entry`, `decision_ledger_entry`): Hibernate knows all
     registered subclasses via JOINED inheritance metadata and deletes the subclass row as part
     of `em.remove()`. The base extension does not need to know consumer subclass names.

**Transaction atomicity:** if archive write fails, the transaction rolls back — nothing is
deleted. An entry is only gone from the main table after it has been safely written to the
archive.

**Error handling:** if one subject's transaction fails, log the error and continue to the
next subject. Partial runs are safe — the job is idempotent (eligible subjects that were
already archived are no longer present in the query results).

---

## Part 6 — Audit Query API

### SPI (`LedgerEntryRepository`)

Three new methods, additive (existing callers unaffected):

```java
/**
 * Return all ledger entries for the given actor in the specified time range, ordered by
 * {@code occurredAt} ascending. Used for auditor-facing reconstructability under
 * EU AI Act Art.12 and GDPR Art.22.
 */
List<LedgerEntry> findByActorId(String actorId, Instant from, Instant to);

/**
 * Return all ledger entries for the given actor role in the specified time range,
 * ordered by {@code occurredAt} ascending.
 */
List<LedgerEntry> findByActorRole(String actorRole, Instant from, Instant to);

/**
 * Return all ledger entries whose {@code occurredAt} falls within the specified range,
 * ordered by {@code occurredAt} ascending.
 */
List<LedgerEntry> findByTimeRange(Instant from, Instant to);
```

**Why `Instant` not `LocalDateTime`:** `occurredAt` is stored as `Instant` throughout the
codebase. An audit query boundary expressed as `LocalDateTime` would require implicit
timezone conversion — a silent correctness hazard for distributed systems writing from
multiple timezones. `Instant` is unambiguous.

### JPA implementation (`JpaLedgerEntryRepository`)

```java
@Override
public List<LedgerEntry> findByActorId(String actorId, Instant from, Instant to) {
    return LedgerEntry.list(
        "actorId = ?1 AND occurredAt >= ?2 AND occurredAt <= ?3 ORDER BY occurredAt ASC",
        actorId, from, to);
}

@Override
public List<LedgerEntry> findByActorRole(String actorRole, Instant from, Instant to) {
    return LedgerEntry.list(
        "actorRole = ?1 AND occurredAt >= ?2 AND occurredAt <= ?3 ORDER BY occurredAt ASC",
        actorRole, from, to);
}

@Override
public List<LedgerEntry> findByTimeRange(Instant from, Instant to) {
    return LedgerEntry.list(
        "occurredAt >= ?1 AND occurredAt <= ?2 ORDER BY occurredAt ASC",
        from, to);
}
```

---

## Part 7 — Testing Strategy

### Unit tests (pure Java, no Quarkus)

**`LedgerEntryArchiverTest`** (new):
- Entry with no supplements → JSON has all core fields, no supplement keys
- Entry with `ComplianceSupplement` → `supplementJson` included in output
- Entry with attestations → attestations array in JSON
- Null fields omitted from output
- Round-trip: JSON can be parsed back to verify all fields present

**`RetentionEligibilityCheckerTest`** (new):
- All entries older than window → subject eligible
- All entries newer than window → subject not eligible
- Exactly at the boundary (age == `operationalDays`) → eligible
- One entry at boundary - 1 day → not eligible
- Mixed: 4 old entries + 1 new entry → subject not eligible (all-or-nothing)
- Empty subject → not returned
- Empty input map → empty output

### Integration tests (`@QuarkusTest`)

**`LedgerRetentionJobIT`** (new) — `retention-test` profile (`retention.enabled=true`, `archive-before-delete=true`):
- Old entries (> `operational-days`) → archived record exists, entry deleted from main table
- New entries (< `operational-days`) → not deleted
- Entry at exactly the limit → archived and deleted
- Entry one day short of limit → untouched
- Subject with mixed ages → none deleted (all-or-nothing)
- Broken chain subject → skipped, both entries remain in main table
- `archive-before-delete=false` → entries deleted, archive table empty
- Attestations deleted before entries (FK constraint not violated — would throw if order wrong)
- Supplement rows deleted (no orphan rows in `ledger_supplement` after deletion)

**`AuditQueryIT`** (new):
- `findByActorId` returns correct entries in range
- `findByActorId` excludes entries outside range
- `findByActorId` with no entries → empty list
- `findByActorRole` returns correct entries
- `findByTimeRange` returns all entries in window
- `findByTimeRange` boundary: entry at `from` included, entry at `to` included

### Example extension

**`examples/order-processing`** — one new IT test demonstrating `findByActorId` after
placing several orders with different actors.

**`examples/art12-compliance/`** (new) — standalone runnable Quarkus app:
- Shows `retention.*` config
- Demonstrates `findByActorId` audit query via REST endpoint
- `README.md` maps Art.12 requirements to ledger features

---

## Part 8 — Compliance Documentation

`docs/compliance/EU-AI-ACT-ART12.md` — requirement → feature mapping table for use
in conformity assessments. Maps each Art.12 obligation to the specific ledger mechanism
that satisfies it, with configuration guidance.

---

## Part 9 — AUDITABILITY.md Updates

After implementation:
- Axiom 5 (Accessibility): mark `findByActorId/Role/TimeRange` as closing the gap
- Axiom 6 (Resource Proportionality): mark retention config as closing the gap

---

## File Map

| File | Action |
|---|---|
| `runtime/src/main/resources/db/migration/V1003__ledger_entry_archive.sql` | Create |
| `runtime/src/main/java/.../model/LedgerEntryArchiveRecord.java` | Create |
| `runtime/src/main/java/.../service/LedgerEntryArchiver.java` | Create |
| `runtime/src/main/java/.../service/RetentionEligibilityChecker.java` | Create |
| `runtime/src/main/java/.../service/LedgerRetentionJob.java` | Create |
| `runtime/src/main/java/.../config/LedgerConfig.java` | Modify — add `RetentionConfig` |
| `runtime/src/main/java/.../repository/LedgerEntryRepository.java` | Modify — add 3 methods |
| `runtime/src/main/java/.../repository/jpa/JpaLedgerEntryRepository.java` | Modify — implement 3 methods |
| `runtime/src/test/java/.../service/LedgerEntryArchiverTest.java` | Create |
| `runtime/src/test/java/.../service/RetentionEligibilityCheckerTest.java` | Create |
| `runtime/src/test/java/.../service/LedgerRetentionJobIT.java` | Create |
| `runtime/src/test/java/.../service/AuditQueryIT.java` | Create |
| `runtime/src/test/resources/application.properties` | Modify — retention-test profile |
| `examples/order-processing/src/main/resources/db/migration/V1003__... → V1004__...` | Rename |
| `examples/art22-decision-snapshot/src/main/resources/db/migration/V1003__... → V1004__...` | Rename |
| `examples/order-processing/src/test/java/.../OrderLedgerIT.java` | Modify — add audit query test |
| `examples/art12-compliance/` | Create — new example |
| `docs/compliance/EU-AI-ACT-ART12.md` | Create |
| `docs/AUDITABILITY.md` | Modify — Axiom 5 + 6 gaps closed |
| `CLAUDE.md` | Modify — Flyway convention V1004+ |
| `docs/DESIGN.md` | Modify — tracker row + convention update |

---

## Zero-Complexity Verification

| Scenario | Retention tables touched? | Existing queries changed? |
|---|---|---|
| `retention.enabled=false` (default) | ❌ No | ❌ No |
| Existing callers using only `findBySubjectId` | ❌ No | ❌ No |
| `archive-before-delete=false` | ❌ No archive rows | — |
| Consumer with no retention config | ❌ No | ❌ No |

---

## References

- Issue #9
- [EU AI Act Article 12 — official text](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-12/)
- [Article 12 compliance checklist (ISMS.online)](https://www.isms.online/iso-42001/eu-ai-act/article-12/)
- `docs/AUDITABILITY.md` — Axiom 5 (Accessibility) and Axiom 6 (Resource Proportionality)
