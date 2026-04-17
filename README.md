# Quarkus Ledger

A Quarkus extension providing a domain-agnostic immutable audit ledger for any Quarkus application.

---

## What you get

### Immutable append-only audit log

Every domain transition is recorded as a `LedgerEntry` row with a monotonically increasing sequence number per aggregate. Entries are never updated or deleted — only appended. Each entry carries who acted (`actorId`, `actorType`, `actorRole`), what happened (`entryType`: COMMAND / EVENT / ATTESTATION), and when (`occurredAt`).

### SHA-256 hash chain

Each entry carries `previousHash` and `digest` fields. The digest is `SHA-256(previousHash | subjectId | seqNum | entryType | actorId | actorRole | occurredAt)`. Any tampering with a historical entry breaks the chain. Verification is offline — no database access needed:

```java
boolean intact = LedgerHashChain.verify(entries); // recomputes all digests from scratch
```

### JPA JOINED inheritance — bring your own domain fields

`LedgerEntry` is abstract. You extend it with a domain-specific subclass that adds your own columns. The base `ledger_entry` table is always present; your subclass table joins on `id`. Multiple consumers coexist in the same deployment without interfering.

### Supplements — optional cross-cutting fields, zero overhead when unused

Three built-in supplement types carry optional fields that not every consumer needs. They live in separate joined tables and are only written when explicitly attached:

| Supplement | Table | Fields | When to use |
|---|---|---|---|
| `ComplianceSupplement` | `ledger_supplement_compliance` | `planRef`, `rationale`, `evidence`, `detail`, `decisionContext`, `algorithmRef`, `confidenceScore`, `contestationUri`, `humanOverrideAvailable` | GDPR Art.22 / EU AI Act Art.12 automated decision auditability |
| `ProvenanceSupplement` | `ledger_supplement_provenance` | `sourceEntityId`, `sourceEntityType`, `sourceEntitySystem` | Entry driven by an external workflow or orchestration system |
| `ObservabilitySupplement` | `ledger_supplement_observability` | `correlationId`, `causedByEntryId` | OTel trace linkage, cross-system causality chains |

Attaching a supplement:
```java
ComplianceSupplement cs = new ComplianceSupplement();
cs.algorithmRef          = "risk-model-v3";
cs.confidenceScore       = 0.91;
cs.humanOverrideAvailable = true;
cs.decisionContext       = "{\"score\":0.91,\"threshold\":0.8}";
entry.attach(cs); // writes to supplement table; also updates entry.supplementJson for fast reads
```

Reading supplement data — two paths:
```java
// Fast path: supplementJson column, no extra query
String json = entry.supplementJson;

// Typed path: lazy join, only when needed
Optional<ComplianceSupplement> cs = entry.compliance();
Optional<ProvenanceSupplement> prov = entry.provenance();
Optional<ObservabilitySupplement> obs = entry.observability();
```

If a consumer never calls `attach()`, no supplement rows are written. Zero schema cost, zero runtime cost.

### Peer attestations

Other actors can stamp a verdict on any ledger entry:

```java
LedgerAttestation attestation = new LedgerAttestation();
attestation.ledgerEntryId = entry.id;
attestation.subjectId     = entry.subjectId;
attestation.attestorId    = "compliance-agent";
attestation.attestorType  = ActorType.AGENT;
attestation.verdict       = AttestationVerdict.SOUND; // SOUND | FLAGGED | ENDORSED | CHALLENGED
attestation.confidence    = 0.95;
attestation.evidence      = "Checked against policy-v2";
attestation.persist();
```

Verdicts feed the EigenTrust reputation system when it is enabled.

### EigenTrust reputation (optional)

A nightly `@Scheduled` job recomputes a trust score `[0.0, 1.0]` per actor from their full ledger history. Algorithm: decision score (1.0 clean / 0.5 mixed / 0.0 predominantly negative), exponentially decayed by age (`weight = 2^(-age/halfLife)`), weighted average across all decisions.

Optional forgiveness mechanism: old isolated failures are partially forgiven based on how recent they are and how infrequent the actor's negative history is. Disabled by default.

Scores are stored in `actor_trust_score` and queryable via `ActorTrustScoreRepository`.

---

## Ecosystem context

```
quarkus-ledger        (audit/provenance — this project)
    ↑         ↑         ↑
 tarkus    qhorus    casehub    (each adds its own LedgerEntry subclass)
    ↑         ↑
          claudony
```

Reference implementations:
- [quarkus-tarkus](https://github.com/mdproctor/quarkus-tarkus) — `WorkItemLedgerEntry` for human task lifecycle audit
- [quarkus-qhorus](https://github.com/mdproctor/quarkus-qhorus) — `AgentMessageLedgerEntry` for AI agent tool-call telemetry

---

## Requirements

- Java 21+
- Quarkus 3.x
- Hibernate ORM with Panache
- Flyway

---

## Add the dependency

**Application:**
```xml
<dependency>
  <groupId>io.quarkiverse.ledger</groupId>
  <artifactId>quarkus-ledger</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

**Quarkus extension deployment module:**
```xml
<dependency>
  <groupId>io.quarkiverse.ledger</groupId>
  <artifactId>quarkus-ledger-deployment</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Also add a JDBC driver. H2 is included as optional for dev/test — add it explicitly:
```xml
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-jdbc-h2</artifactId>
</dependency>
```

---

## Base tables (created automatically)

| Migration | Table | Purpose |
|---|---|---|
| V1000 | `ledger_entry` | Base audit record — core fields + discriminator |
| V1000 | `ledger_attestation` | Peer verdicts — FK to `ledger_entry.id` |
| V1001 | `actor_trust_score` | Nightly EigenTrust scores per actor |
| V1002 | `ledger_supplement` | Supplement base table |
| V1002 | `ledger_supplement_compliance` | Compliance fields |
| V1002 | `ledger_supplement_provenance` | Provenance fields |
| V1002 | `ledger_supplement_observability` | OTel / causality fields |

**Flyway version numbering convention:** V1000–V1002 are reserved by this extension. Your domain tables go in V1–V999; your subclass join tables go in V1003+.

---

## Quick start — 4 steps

**1. Define your subclass:**
```java
@Entity
@Table(name = "order_ledger_entry")
@DiscriminatorValue("ORDER")
public class OrderLedgerEntry extends LedgerEntry {
    @Column(name = "order_id", nullable = false) public UUID orderId;
    @Column(name = "transition_type")            public String transitionType;
}
```

**2. Add a Flyway migration (V1003 or later):**
```sql
CREATE TABLE order_ledger_entry (
    id              UUID NOT NULL,
    order_id        UUID NOT NULL,
    transition_type VARCHAR(100),
    CONSTRAINT pk_order_ledger_entry PRIMARY KEY (id),
    CONSTRAINT fk_order_ledger_entry FOREIGN KEY (id) REFERENCES ledger_entry (id)
);
```

**3. Write to the ledger:**
```java
OrderLedgerEntry entry = new OrderLedgerEntry();
entry.subjectId      = order.id;   // scopes sequence + hash chain per order
entry.orderId        = order.id;
entry.sequenceNumber = nextSeq(order.id);
entry.entryType      = LedgerEntryType.EVENT;
entry.transitionType = "PlaceOrder";
entry.actorId        = currentUser;
entry.actorType      = ActorType.HUMAN;
entry.actorRole      = "Customer";
entry.occurredAt     = Instant.now().truncatedTo(ChronoUnit.MILLIS); // set before hash!

if (config.hashChain().enabled()) {
    entry.previousHash = previousDigest(order.id);
    entry.digest = LedgerHashChain.compute(entry.previousHash, entry);
}
entry.persist();
```

**4. Verify chain integrity:**
```java
List<OrderLedgerEntry> entries = OrderLedgerEntry
    .list("subjectId = ?1 ORDER BY sequenceNumber ASC", orderId);
boolean intact = LedgerHashChain.verify(entries);
```

---

## Core entity fields

| Field | Type | Description |
|---|---|---|
| `id` | UUID | Primary key (auto-assigned on persist) |
| `subjectId` | UUID | Aggregate identifier — scopes sequence numbering and hash chain |
| `sequenceNumber` | int | 1-based position in this subject's ledger |
| `entryType` | LedgerEntryType | `COMMAND`, `EVENT`, or `ATTESTATION` |
| `actorId` | String | Identity of the actor |
| `actorType` | ActorType | `HUMAN`, `AGENT`, or `SYSTEM` |
| `actorRole` | String | Functional role (e.g. "Approver", "Resolver") |
| `occurredAt` | Instant | When recorded — set this explicitly before computing the hash |
| `previousHash` | String | SHA-256 digest of the preceding entry (`null` for the first) |
| `digest` | String | SHA-256 digest of this entry's canonical content |
| `supplementJson` | String | JSON snapshot of all attached supplements (auto-set by `attach()`) |

Optional fields — attach as supplements (see above): `correlationId`, `causedByEntryId` (ObservabilitySupplement); `planRef`, `rationale`, `evidence`, `detail`, `decisionContext`, `algorithmRef`, `confidenceScore`, `contestationUri`, `humanOverrideAvailable` (ComplianceSupplement); `sourceEntityId`, `sourceEntityType`, `sourceEntitySystem` (ProvenanceSupplement).

---

## Configuration

All keys are under `quarkus.ledger`:

| Key | Default | Description |
|---|---|---|
| `quarkus.ledger.enabled` | `true` | Master switch — disables all ledger writes when false |
| `quarkus.ledger.hash-chain.enabled` | `true` | Compute and store SHA-256 digests |
| `quarkus.ledger.decision-context.enabled` | `true` | Gate: populate `ComplianceSupplement.decisionContext` |
| `quarkus.ledger.evidence.enabled` | `false` | Gate: populate `ComplianceSupplement.evidence` |
| `quarkus.ledger.attestations.enabled` | `true` | Enable peer attestation persistence |
| `quarkus.ledger.trust-score.enabled` | `false` | Enable nightly EigenTrust computation |
| `quarkus.ledger.trust-score.decay-half-life-days` | `90` | Age decay half-life for trust scoring |
| `quarkus.ledger.trust-score.routing-enabled` | `false` | Fire CDI events when trust scores influence routing |
| `quarkus.ledger.trust-score.forgiveness.enabled` | `false` | Partially forgive old isolated failures |
| `quarkus.ledger.trust-score.forgiveness.frequency-threshold` | `3` | Negative decisions ≤ this receive full leniency; above → half |
| `quarkus.ledger.trust-score.forgiveness.half-life-days` | `30` | Forgiveness decay half-life |

---

## Documentation

| Doc | Contents |
|---|---|
| [Integration Guide](docs/integration-guide.md) | Step-by-step: subclass, migration, repository, capture, supplements, queries, configuration |
| [Design Document](docs/DESIGN.md) | Architecture decisions, supplement rationale, Flyway conventions, roadmap |
| [Auditability Assessment](docs/AUDITABILITY.md) | 8-axiom self-assessment against ACM FAIR 2025 framework |
| [Examples](docs/examples.md) | Complete worked example — order processing domain |
| [Runnable Example — Order Processing](examples/order-processing/) | `mvn quarkus:dev` — order lifecycle with ledger, hash chain, attestations |
| [Runnable Example — GDPR Art.22](examples/art22-decision-snapshot/) | `mvn quarkus:dev` — AI decision service with full Art.22 compliance supplement |

---

## License

Apache 2.0
