# Remove Panache from Internal Entities — Design Spec

**Date:** 2026-04-20
**Goal:** Make all casehub-ledger entities plain `@Entity` POJOs. Remove `quarkus-hibernate-orm-panache` from the runtime pom. No consumer impact — only internal entities are changing.

---

## Motivation

`LedgerEntry` was already made a plain `@Entity` to enable reactive subclassing by Qhorus (issue #16). The remaining entities still extend `PanacheEntityBase`, creating an inconsistent model layer and an unnecessary forced dependency on blocking Panache for any consumer of the extension. Removing Panache from internal entities completes the cleanup.

---

## Scope

### Entities losing PanacheEntityBase (internal — never subclassed by consumers)

| Entity | File | Current usage |
|---|---|---|
| `LedgerMerkleFrontier` | `model/LedgerMerkleFrontier.java` | `findBySubjectId()`, `deleteBySubjectAndLevel()`, `node.persist()` |
| `LedgerAttestation` | `model/LedgerAttestation.java` | `LedgerAttestation.list(...)`, `attestation.persist()` |
| `LedgerSupplement` (abstract) | `model/supplement/LedgerSupplement.java` | persisted via JPA cascade — no explicit calls |
| `ComplianceSupplement` | `model/supplement/ComplianceSupplement.java` | persisted via cascade |
| `ProvenanceSupplement` | `model/supplement/ProvenanceSupplement.java` | persisted via cascade |
| `ActorTrustScore` | `model/ActorTrustScore.java` | `score.persist()` in `JpaActorTrustScoreRepository` |
| `LedgerEntryArchiveRecord` | `model/LedgerEntryArchiveRecord.java` | `record.persist()` in `LedgerRetentionJob` |

### pom.xml

Remove `quarkus-hibernate-orm-panache`. Add `quarkus-hibernate-orm` directly if not already a transitive dep (verify).

### Implementation files requiring updates

| File | Change |
|---|---|
| `JpaLedgerEntryRepository` | Replace `LedgerAttestation.list(...)` / `attestation.persist()` with EntityManager JPQL / `em.persist()` |
| `JpaActorTrustScoreRepository` | Replace `score.persist()` with `em.persist(score)` |
| `LedgerRetentionJob` | Replace `record.persist()` with `em.persist(record)` — already has `@Inject EntityManager` |

---

## Query Approach — @NamedQuery

All JPQL strings move to `@NamedQuery` annotations on the entity class. Hibernate validates them at application startup — a typo fails boot, not at query time.

```java
@Entity
@Table(name = "ledger_attestation")
@NamedQuery(
    name = "LedgerAttestation.findByEntryId",
    query = "SELECT a FROM LedgerAttestation a WHERE a.ledgerEntryId = :id ORDER BY a.occurredAt ASC")
@NamedQuery(
    name = "LedgerAttestation.findBySubjectId",
    query = "SELECT a FROM LedgerAttestation a WHERE a.subjectId = :id ORDER BY a.occurredAt ASC")
@NamedQuery(
    name = "LedgerAttestation.findByEntryIds",
    query = "SELECT a FROM LedgerAttestation a WHERE a.ledgerEntryId IN :ids")
public class LedgerAttestation { ... }
```

Repository usage:
```java
em.createNamedQuery("LedgerAttestation.findByEntryId", LedgerAttestation.class)
  .setParameter("id", ledgerEntryId)
  .getResultList()
```

Apply the same pattern to `LedgerMerkleFrontier` and `ActorTrustScore`.

---

## Named Queries Required

### LedgerMerkleFrontier

| Name | Query |
|---|---|
| `LedgerMerkleFrontier.findBySubjectId` | `SELECT f FROM LedgerMerkleFrontier f WHERE f.subjectId = :subjectId ORDER BY f.level ASC` |
| `LedgerMerkleFrontier.deleteBySubjectAndLevel` | `DELETE FROM LedgerMerkleFrontier f WHERE f.subjectId = :subjectId AND f.level = :level` |

### LedgerAttestation

| Name | Query |
|---|---|
| `LedgerAttestation.findByEntryId` | `SELECT a FROM LedgerAttestation a WHERE a.ledgerEntryId = :id ORDER BY a.occurredAt ASC` |
| `LedgerAttestation.findBySubjectId` | `SELECT a FROM LedgerAttestation a WHERE a.subjectId = :id ORDER BY a.occurredAt ASC` |
| `LedgerAttestation.findByEntryIds` | `SELECT a FROM LedgerAttestation a WHERE a.ledgerEntryId IN :ids` |

### ActorTrustScore

| Name | Query |
|---|---|
| `ActorTrustScore.findByActorId` | `SELECT s FROM ActorTrustScore s WHERE s.actorId = :actorId` |
| `ActorTrustScore.findAll` | `SELECT s FROM ActorTrustScore s` |

---

## Supplements — No Query Changes

`LedgerSupplement`, `ComplianceSupplement`, `ProvenanceSupplement` persist via `CascadeType.ALL` on `LedgerEntry.supplements`. No explicit `supplement.persist()` is called anywhere in production code. Removing `PanacheEntityBase` from these classes changes nothing functionally — just remove the `extends` clause and the import.

---

## Testing

- All 129 existing tests must pass after the change
- `@NamedQuery` validation provides startup-time correctness guarantee for all JPQL
- No new test files needed — existing ITs cover all affected paths

## What Does NOT Change

- All entity field names, column names, table names, JPA annotations
- `LedgerEntryRepository` and `ReactiveLedgerEntryRepository` SPI interfaces
- Consumer subclasses (`WorkItemLedgerEntry`, `AgentMessageLedgerEntry`) — defined in consumer projects, unaffected
- Supplement cascade behaviour — `CascadeType.ALL` on `LedgerEntry.supplements` remains
