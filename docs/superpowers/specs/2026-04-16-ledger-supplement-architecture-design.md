# Design Spec — LedgerSupplement Architecture + ComplianceSupplement

**Date:** 2026-04-16
**Issue:** #7 — GDPR Art.22 decision snapshot
**Epic:** #6 — Agentic AI compliance and trust quality
**Status:** Approved

---

## Problem

`LedgerEntry` has accumulated 20 fields, most of them nullable, covering multiple
unrelated optional concerns: compliance (GDPR Art.22, EU AI Act), provenance
(workflow source tracking), and observability (OTel tracing, causality). This creates
a wide-table anti-pattern: developers reading the entity see fields that are irrelevant
to their use case, with no clear signal about which fields belong together or when to
populate them.

Adding more optional fields for Art.22 (algorithmRef, confidenceScore, contestationUri,
humanOverrideAvailable) would compound the problem. Instead, this spec introduces a
clean architectural pattern — `LedgerSupplement` — and uses the Art.22 fields as its
first concrete application.

---

## Design Constraint

> **If a consumer does not use a supplement, nothing changes.** No new config,
> no new beans, no new boilerplate. Secondary tables are never touched unless the
> consumer explicitly attaches a supplement to an entry.

This is the zero-complexity constraint from `docs/AUDITABILITY.md`. Every decision
in this spec is evaluated against it.

---

## Architecture

### Core Entity — `LedgerEntry` (slimmed to 10 fields)

The base entity is reduced to fields that are relevant for every entry, every consumer,
every time. No exceptions.

**Retained core fields:**

| Field | Purpose |
|---|---|
| `id` | Primary key |
| `subjectId` | Aggregate identifier — scopes sequence and hash chain |
| `sequenceNumber` | Per-subject monotonic position |
| `entryType` | `COMMAND` / `EVENT` / `ATTESTATION` |
| `actorId` | Identity of the actor |
| `actorType` | `HUMAN` / `AGENT` / `SYSTEM` |
| `actorRole` | Functional role of the actor |
| `occurredAt` | When the entry was recorded |
| `previousHash` | SHA-256 of the previous entry in the chain |
| `digest` | SHA-256 of this entry's canonical content |

**Removed from core (moved to supplements):**

| Field | Moved to |
|---|---|
| `planRef` | `ComplianceSupplement` |
| `rationale` | `ComplianceSupplement` |
| `evidence` | `ComplianceSupplement` |
| `detail` | `ComplianceSupplement` |
| `decisionContext` | `ComplianceSupplement` |
| `correlationId` | `ObservabilitySupplement` |
| `causedByEntryId` | `ObservabilitySupplement` |
| `sourceEntityId` | `ProvenanceSupplement` |
| `sourceEntityType` | `ProvenanceSupplement` |
| `sourceEntitySystem` | `ProvenanceSupplement` |

**New fields on `LedgerEntry` supporting the supplement system:**

```java
// Lazy — never loaded unless accessed. Never written unless consumer adds a supplement.
@OneToMany(mappedBy = "ledgerEntry", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
@MapKey(name = "supplementType")
public Map<SupplementType, LedgerSupplement> supplements = new HashMap<>();

// Denormalised JSON snapshot of all attached supplements.
// Written explicitly by the service layer before persist — not via JPA hook.
// Enables fast single-entry reads without joins.
@Column(name = "supplement_json", columnDefinition = "TEXT")
public String supplementJson;
```

### Hash Chain — Canonical Form Unchanged

The hash chain canonical form remains:
```
subjectId|seqNum|entryType|actorId|actorRole|occurredAt
```
(Note: `planRef` was previously in the canonical form but is now in `ComplianceSupplement`.
The canonical form is updated to remove it — see Migration section.)

Supplements are deliberately excluded from the canonical form. The chain covers the
immutable core audit record. Compliance and provenance metadata are enrichment, not
tamper-evidence targets.

### Supplement Base Entity — `LedgerSupplement`

Abstract JPA entity using the same JOINED inheritance strategy as `LedgerEntry` itself.

```
ledger_supplement (base table)
  id UUID PK
  ledger_entry_id UUID FK → ledger_entry.id
  supplement_type VARCHAR(30) — discriminator

  ├── ledger_supplement_compliance    → ComplianceSupplement
  ├── ledger_supplement_provenance    → ProvenanceSupplement
  └── ledger_supplement_observability → ObservabilitySupplement
```

```java
@Entity
@Inheritance(strategy = InheritanceType.JOINED)
@DiscriminatorColumn(name = "supplement_type", discriminatorType = DiscriminatorType.STRING)
@Table(name = "ledger_supplement")
public abstract class LedgerSupplement extends PanacheEntityBase {
    @Id public UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ledger_entry_id", nullable = false)
    public LedgerEntry ledgerEntry;

    @Enumerated(EnumType.STRING)
    @Column(name = "supplement_type", insertable = false, updatable = false)
    public SupplementType supplementType;

    @PrePersist
    void prePersist() { if (id == null) id = UUID.randomUUID(); }
}
```

```java
public enum SupplementType {
    COMPLIANCE,     // GDPR Art.22, EU AI Act Art.12, governance
    PROVENANCE,     // Source entity / workflow origin tracking
    OBSERVABILITY   // OTel tracing, cross-system causality
}
```

### ComplianceSupplement (delivered in this issue)

Covers governance, rationale, and GDPR Art.22 structured decision snapshot.

```java
@Entity
@Table(name = "ledger_supplement_compliance")
@DiscriminatorValue("COMPLIANCE")
public class ComplianceSupplement extends LedgerSupplement {

    // Governance
    @Column(name = "plan_ref")
    public String planRef;                    // policy/procedure version reference

    public String rationale;                  // actor's stated basis for the decision

    public String evidence;                   // structured evidence supplied by actor

    public String detail;                     // free-text overflow (delegation targets, etc.)

    // GDPR Art.22 / EU AI Act decision snapshot
    @Column(name = "decision_context", columnDefinition = "TEXT")
    public String decisionContext;            // full JSON snapshot of observable state

    @Column(name = "algorithm_ref")
    public String algorithmRef;              // model/algorithm/rule-engine identifier

    @Column(name = "confidence_score")
    public Double confidenceScore;           // 0.0–1.0, producer's stated confidence

    @Column(name = "contestation_uri", length = 2000)
    public String contestationUri;           // URI where data subject can request review

    @Column(name = "human_override_available")
    public Boolean humanOverrideAvailable;   // whether a human review path exists
}
```

### ProvenanceSupplement (structure defined, delivered in this issue)

Covers workflow source entity tracking.

```java
@Entity
@Table(name = "ledger_supplement_provenance")
@DiscriminatorValue("PROVENANCE")
public class ProvenanceSupplement extends LedgerSupplement {
    @Column(name = "source_entity_id")     public String sourceEntityId;
    @Column(name = "source_entity_type")   public String sourceEntityType;
    @Column(name = "source_entity_system") public String sourceEntitySystem;
}
```

### ObservabilitySupplement (structure defined, delivered in this issue)

Covers distributed tracing and cross-system causality.

```java
@Entity
@Table(name = "ledger_supplement_observability")
@DiscriminatorValue("OBSERVABILITY")
public class ObservabilitySupplement extends LedgerSupplement {
    @Column(name = "correlation_id")      public String correlationId;
    @Column(name = "caused_by_entry_id")  public UUID causedByEntryId;
}
```

### supplementJson — Denormalised Read Path

The `supplementJson` TEXT column on `ledger_entry` holds a JSON snapshot of all
supplements attached to an entry. It is written by the service layer immediately
before persist — not via a JPA lifecycle hook — to avoid loading the lazy collection.

Format:
```json
{
  "COMPLIANCE": {
    "planRef": "...",
    "algorithmRef": "gpt-4o",
    "confidenceScore": 0.92,
    "contestationUri": "https://...",
    "humanOverrideAvailable": true
  },
  "OBSERVABILITY": {
    "correlationId": "abc123"
  }
}
```

A `LedgerSupplementSerializer` utility class handles serialisation and deserialisation
using **Jackson** (`quarkus-rest-jackson`, already on the classpath via the example app).

---

## Flyway Migrations

| Version | File | What |
|---|---|---|
| V1002 | `V1002__ledger_supplement.sql` | Drop removed columns from `ledger_entry`; add `supplement_json`; create `ledger_supplement`, `ledger_supplement_compliance`, `ledger_supplement_provenance`, `ledger_supplement_observability` tables |

**The canonical hash form change:** `planRef` is removed from `ledger_entry` and from
the canonical form. Existing chains computed with `planRef` included are invalidated.
Since no production data exists (v1.0.0-SNAPSHOT, all consumers are also pre-release),
this is acceptable. The migration drops and recreates — no data migration needed.
`docs/DESIGN.md` § Hash chain canonical form must be updated to remove `planRef`.

**Flyway version coordination:** The base extension reserves V1000–V1002. Consumer
subclass join tables must use **V1003+** (updated from the previous V1002+ convention).
Qhorus's existing `V1002__agent_message_ledger_entry.sql` must be renumbered to V1003.
All consumers are pre-release — this is a coordinated but low-risk rename.

---

## Zero-Complexity Verification

| Consumer action | Supplement tables touched? | supplementJson written? |
|---|---|---|
| Write entry, set no supplements | ❌ No | ❌ No (null) |
| Write entry, set ComplianceSupplement only | ✅ compliance only | ✅ COMPLIANCE key only |
| Read entry, never access `.supplements` | ❌ No (lazy) | — |
| Read entry, call `.supplements.get(COMPLIANCE)` | ✅ compliance only | — |
| Read supplementJson field | ❌ No joins | — |

Existing consumers (`WorkItemLedgerEntry` in Tarkus, `AgentMessageLedgerEntry` in
Qhorus) compile and pass all tests without any changes, provided they are not
currently setting the fields being removed from `LedgerEntry`. ⚠️ **Coordination
required:** Tarkus and Qhorus must update to the new supplement API to use `planRef`,
`rationale`, `correlationId`, `sourceEntityId` etc. They are pre-release so this is
acceptable, but it is a breaking change to their current integration.

---

## Testing Strategy

### Unit tests (runtime module)

- `LedgerSupplementTest` — new
  - Supplement round-trip: write ComplianceSupplement, read back all fields
  - Supplement round-trip: all three supplement types independently
  - `supplementJson` serialises only attached supplements (not null keys)
  - `supplementJson` deserialises correctly back to typed POJOs
  - Entry with no supplements: `supplementJson` is null, supplement tables untouched
  - Map key: `supplements.get(COMPLIANCE)` returns correct type

- `LedgerHashChainTest` — updated
  - Hash chain unaffected when ComplianceSupplement is attached (canonical form
    does not include supplement fields)
  - Canonical form no longer includes `planRef` — verify updated test vectors

### Integration tests (order-processing example)

- Extend existing `OrderLedgerIT`:
  - New test: place order with `ComplianceSupplement` (algorithmRef + confidenceScore
    + contestationUri) — verify fields returned in ledger API response
  - New test: place order without supplement — verify `ledger_supplement` table row
    count is 0 for that entry (secondary table not touched)
  - New test: `supplementJson` returned in response matches attached supplement fields

### New example — `examples/art22-decision-snapshot/`

A standalone runnable Quarkus app demonstrating:
- An AI decision-making service that attaches `ComplianceSupplement` to each decision
- All four Art.22 fields populated
- REST endpoint returning the decision history with full compliance snapshot
- `README.md` explaining GDPR Art.22, what each field satisfies, and the regulatory
  context (right to explanation, contestation mechanism, human oversight)

---

## Impact on Existing Consumers

| Consumer | Impact | Action required |
|---|---|---|
| `quarkus-tarkus` | Uses `planRef`, `rationale`, `correlationId` — these move to supplements | Update `WorkItemLedgerEntry` capture to use `ComplianceSupplement` / `ObservabilitySupplement` |
| `quarkus-qhorus` | Uses `decisionContext`, `sourceEntityId/Type/System`, `correlationId` | Update `AgentMessageLedgerEntry` capture to use all three supplements |
| `examples/order-processing` | Uses `planRef`, `rationale`, `decisionContext` | Update `OrderService` to use `ComplianceSupplement` |

All three are pre-release. Coordination is required but not blocked.

---

## Open Questions

None. All design decisions resolved.

---

## References

- `docs/AUDITABILITY.md` — zero-complexity constraint and axiom gap analysis
- `docs/RESEARCH.md` — research basis for Art.22 field selection
- Issue #7 — GDPR Art.22 decision snapshot
- Issue #6 — Epic: Agentic AI compliance and trust quality
