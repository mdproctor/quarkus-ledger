# Quarkus Ledger

A Quarkus extension providing a domain-agnostic immutable audit ledger for any Quarkus application.

---

## What you get

### Immutable append-only audit log

Every domain transition is recorded as a `LedgerEntry` row with a monotonically increasing sequence number per aggregate. Entries are never updated or deleted — only appended. Each entry carries who acted (`actorId`, `actorType`, `actorRole`), what happened (`entryType`: COMMAND / EVENT / ATTESTATION), and when (`occurredAt`).

### Merkle Mountain Range tamper evidence

Each entry carries a `digest` — a SHA-256 leaf hash (RFC 9162) over its canonical fields. Entries accumulate into a Merkle Mountain Range: a compact stored frontier that gives O(log N) inclusion proofs. Any entry modification is detectable without replaying the entire chain:

```java
boolean intact = verificationService.verify(subjectId); // O(N log N), no trust in operator needed
InclusionProof proof = verificationService.inclusionProof(entryId); // compact cryptographic proof
```

### JPA JOINED inheritance — bring your own domain fields

`LedgerEntry` is abstract. You extend it with a domain-specific subclass that adds your own columns. The base `ledger_entry` table is always present; your subclass table joins on `id`. Multiple consumers coexist in the same deployment without interfering.

### Supplements — optional cross-cutting fields, zero overhead when unused

Three built-in supplement types carry optional fields that not every consumer needs. They live in separate joined tables and are only written when explicitly attached:

| Supplement | Table | Fields | When to use |
|---|---|---|---|
| `ComplianceSupplement` | `ledger_supplement_compliance` | `planRef`, `rationale`, `evidence`, `detail`, `decisionContext`, `algorithmRef`, `confidenceScore`, `contestationUri`, `humanOverrideAvailable` | GDPR Art.22 / EU AI Act Art.12 automated decision auditability |
| `ProvenanceSupplement` | `ledger_supplement_provenance` | `sourceEntityId`, `sourceEntityType`, `sourceEntitySystem` | Entry driven by an external workflow or orchestration system |

OTel trace linkage (`traceId`) and cross-subject causality (`causedByEntryId`) are core fields on every entry — not supplements.

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

Verdicts feed the Bayesian Beta trust scoring system when it is enabled.

### Bayesian Beta trust scoring (optional)

A nightly `@Scheduled` job recomputes a trust score `[0.0, 1.0]` per actor from their full attestation history. Algorithm: Bayesian Beta accumulation — `α` increments for positive verdicts (SOUND, ENDORSED), `β` for negative (FLAGGED, CHALLENGED). Each contribution is recency-weighted: `weight = 2^(-ageInDays / halfLifeDays)`. Prior is Beta(1,1) — score 0.5 with no history. Score = α/(α+β).

Disabled by default. Enable once attestation history has accumulated — early scores with sparse data are unreliable.

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
- Hibernate ORM

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

**2. Add a migration for your subclass table** (using whichever schema tool your app uses).

**3. Write to the ledger:**
```java
OrderLedgerEntry entry = new OrderLedgerEntry();
entry.subjectId      = order.id;   // scopes sequence + Merkle chain per order
entry.orderId        = order.id;
entry.sequenceNumber = nextSeq(order.id);
entry.entryType      = LedgerEntryType.EVENT;
entry.transitionType = "PlaceOrder";
entry.actorId        = currentUser;
entry.actorType      = ActorType.HUMAN;
entry.actorRole      = "Customer";
entry.occurredAt     = Instant.now().truncatedTo(ChronoUnit.MILLIS); // set before repo.save()!

repo.save(entry); // computes Merkle leaf hash and updates MMR frontier automatically
```

**4. Verify chain integrity:**
```java
@Inject LedgerVerificationService verificationService;
// ...
boolean intact = verificationService.verify(orderId); // O(N log N), offline-verifiable
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
| `traceId` | String | OTel trace ID linking this entry to a distributed trace |
| `causedByEntryId` | UUID | FK to causal predecessor entry (cross-subject causality) |
| `digest` | String | RFC 9162 Merkle leaf hash — `SHA-256(0x00 \| canonical fields)` |
| `supplementJson` | String | JSON snapshot of all attached supplements (auto-set by `attach()`) |

Optional fields — attach as supplements (see above): `planRef`, `rationale`, `evidence`, `detail`, `decisionContext`, `algorithmRef`, `confidenceScore`, `contestationUri`, `humanOverrideAvailable` (ComplianceSupplement); `sourceEntityId`, `sourceEntityType`, `sourceEntitySystem` (ProvenanceSupplement).

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
| `quarkus.ledger.trust-score.enabled` | `false` | Enable nightly Bayesian Beta trust score computation |
| `quarkus.ledger.trust-score.decay-half-life-days` | `90` | Age decay half-life for attestation recency weighting |
| `quarkus.ledger.trust-score.routing-enabled` | `false` | Fire CDI events when trust scores influence routing |
| `quarkus.ledger.trust-score.routing-delta-threshold` | `0.01` | Minimum score change for an actor to appear in a delta routing event |
| `quarkus.ledger.identity.tokenisation.enabled` | `false` | Enable built-in UUID token pseudonymisation for actor identities |

---

## Documentation

| Doc | Contents |
|---|---|
| [Capabilities Guide](docs/CAPABILITIES.md) | Every capability explained — applicability ratings, regulatory drivers, when to enable |
| [Consumer Privacy Obligations](docs/PRIVACY.md) | What consumers must decide: `actorId` tokenisation, `decisionContext` sanitisation, GDPR Art.17 erasure |
| [Integration Guide](docs/integration-guide.md) | Step-by-step: subclass, migration, repository, capture, supplements, queries, configuration |
| [Design Document](docs/DESIGN.md) | Architecture decisions, supplement rationale, roadmap |
| [Auditability Assessment](docs/AUDITABILITY.md) | 8-axiom self-assessment against ACM FAIR 2025 framework |
| [Examples](docs/examples.md) | Complete worked example — order processing domain |
| [Runnable Example — Order Processing](examples/order-processing/) | `mvn quarkus:dev` — order lifecycle with ledger, hash chain, attestations |
| [Runnable Example — GDPR Art.22](examples/art22-decision-snapshot/) | `mvn quarkus:dev` — AI decision service with full Art.22 compliance supplement |
| [Runnable Example — PROV-DM export](examples/prov-dm-export/) | `mvn quarkus:dev` — W3C PROV-DM JSON-LD export from audit entries |
| [Runnable Example — Trust Score Routing](examples/trust-score-routing/) | `mvn quarkus:dev` — CDI routing signals after trust score computation |

---

## License

Apache 2.0
