# #60 capabilityTag on LedgerAttestation — Spec

**Date:** 2026-05-01  
**Status:** Approved  
**Issue:** #60 (Group B epic #50)  
**Blocks:** #61 (capability-scoped trust scores), #62 (multi-dimensional trust)

## Goal

Add a nullable `capabilityTag` field to `LedgerAttestation` so trust scoring can be scoped per capability. A null tag means the attestation is global (applies to all capabilities). A non-null tag (e.g. `"security-review"`) scopes the verdict to that capability only.

## Schema

Rewrite `V1000__ledger_base_schema.sql` in place — no new migration file. Add to `ledger_attestation` CREATE TABLE:

```sql
capability_tag   VARCHAR(255)
```

Add two new indexes:

```sql
CREATE INDEX idx_ledger_attestation_capability ON ledger_attestation (ledger_entry_id, capability_tag);
CREATE INDEX idx_ledger_attestation_actor_cap  ON ledger_attestation (attestor_id, capability_tag);
```

The composite `(ledger_entry_id, capability_tag)` covers the two entry-scoped queries. The composite `(attestor_id, capability_tag)` covers the actor+capability query needed by B2.

## Model

**`api/model/LedgerAttestation.java`** (`@MappedSuperclass`) — add one field:

```java
@Column(name = "capability_tag")
public String capabilityTag;  // nullable — null means global
```

**`runtime/model/LedgerAttestation.java`** (`@Entity`) — add three `@NamedQuery` annotations:

```java
@NamedQuery(
    name = "LedgerAttestation.findByEntryIdAndCapabilityTag",
    query = "SELECT a FROM LedgerAttestation a WHERE a.ledgerEntryId = :entryId AND a.capabilityTag = :capabilityTag ORDER BY a.occurredAt ASC")
@NamedQuery(
    name = "LedgerAttestation.findGlobalByEntryId",
    query = "SELECT a FROM LedgerAttestation a WHERE a.ledgerEntryId = :entryId AND a.capabilityTag IS NULL ORDER BY a.occurredAt ASC")
@NamedQuery(
    name = "LedgerAttestation.findByAttestorIdAndCapabilityTag",
    query = "SELECT a FROM LedgerAttestation a WHERE a.attestorId = :attestorId AND a.capabilityTag = :capabilityTag ORDER BY a.occurredAt ASC")
```

## Repository SPI

**`LedgerEntryRepository`** — add three methods:

```java
List<LedgerAttestation> findAttestationsByEntryIdAndCapabilityTag(UUID entryId, String capabilityTag);
List<LedgerAttestation> findGlobalAttestationsByEntryId(UUID entryId);
List<LedgerAttestation> findAttestationsByAttestorIdAndCapabilityTag(String attestorId, String capabilityTag);
```

**`ReactiveLedgerEntryRepository`** — add three mirrored methods (required by `ReactiveRepositoryIT.reactiveSpi_coversAllBlockingSpiMethods`):

```java
Uni<List<LedgerAttestation>> findAttestationsByEntryIdAndCapabilityTag(UUID entryId, String capabilityTag);
Uni<List<LedgerAttestation>> findGlobalAttestationsByEntryId(UUID entryId);
Uni<List<LedgerAttestation>> findAttestationsByAttestorIdAndCapabilityTag(String attestorId, String capabilityTag);
```

`findAllEvents` remains the only intentional blocking-only exclusion from the reactive SPI.

**`JpaLedgerEntryRepository`** — implement the three blocking methods. The third method runs `attestorId` through `actorIdentityProvider.tokeniseForQuery()` before querying, matching the pattern of `findByActorId`.

## Tests

**New class:** `LedgerAttestationCapabilityIT` (`@QuarkusTest`)

| Test | Assertion |
|---|---|
| Attestation with `capabilityTag` set | Stored and retrieved correctly |
| Attestation with `capabilityTag = null` | Returned by `findGlobalAttestationsByEntryId`, not by capability query |
| `findAttestationsByEntryIdAndCapabilityTag` | Returns only matching tag; excludes other tags on same entry |
| `findAttestationsByAttestorIdAndCapabilityTag` | Returns attestations across multiple entries for same actor+capability |
| Existing attestation tests | All pass unchanged — null capabilityTag is backward-compatible |

**`LedgerTestFixtures`** — add an overload of `seedDecision` accepting a `capabilityTag` parameter for use by B2 tests. Existing overloads unchanged.

## Out of Scope

- Updating `LedgerWriteService` in `casehub-qhorus` to pass capability tags — deferred, coordinated separately.
- Any changes to `TrustScoreJob` to consume `capabilityTag` — that is B2 (#61).
