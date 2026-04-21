# Integration Guide

This guide walks through everything needed to add `quarkus-ledger` to a Quarkus application or extension.

---

## How it works

`quarkus-ledger` uses JPA JOINED inheritance. The base `ledger_entry` table holds all domain-agnostic fields (actor, sequence, Merkle leaf hash, provenance, decision context). Your domain subclass adds a sibling table that joins on `id`.

```
ledger_entry (base — created by quarkus-ledger V1000)
  ├── order_ledger_entry         ← your subclass table
  ├── work_item_ledger_entry     ← quarkus-tarkus subclass
  └── agent_message_ledger_entry ← quarkus-qhorus subclass
```

`LedgerAttestation` rows reference `ledger_entry.id` directly — attestations work across all subclasses without any changes.

---

## Step 1 — Add the dependency

### Application POM

```xml
<dependency>
  <groupId>io.quarkiverse.ledger</groupId>
  <artifactId>quarkus-ledger</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Extension deployment POM (if building a Quarkus extension)

```xml
<dependency>
  <groupId>io.quarkiverse.ledger</groupId>
  <artifactId>quarkus-ledger-deployment</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Flyway picks up `quarkus-ledger`'s migrations (V1000, V1001, V1002, V1003) automatically from the classpath. Your own migrations start from a lower number (e.g. V1–V9) or a distinct high range (e.g. V2000+). Do not use V1000–V1003.

> **Critical:** your subclass migration must run **after** quarkus-ledger's V1000 (which creates `ledger_entry`), because the subclass table has a `FOREIGN KEY ... REFERENCES ledger_entry (id)`. If you number your subclass migration V5 and the base schema is V1000, Flyway will try to create the FK before the parent table exists and fail with `Table "LEDGER_ENTRY" not found`.
>
> Safe numbering: use V1–V999 for your domain tables and **V1004+ for any subclass join tables** (V1000–V1003 are reserved by `quarkus-ledger`). See the [example](../examples/order-processing/) where `V1004__order_ledger_entry.sql` follows this rule.

---

## Step 2 — Create your LedgerEntry subclass

```java
import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "order_ledger_entry")
@DiscriminatorValue("ORDER")
public class OrderLedgerEntry extends LedgerEntry {

    /**
     * The Order this entry belongs to — redundant with subjectId but typed for clarity
     * and direct queryability without a cast.
     */
    @Column(name = "order_id", nullable = false)
    public UUID orderId;

    /**
     * The command type — e.g. "PlaceOrder", "CancelOrder", "ShipOrder".
     */
    @Column(name = "command_type")
    public String commandType;

    /**
     * The observable event type — e.g. "OrderPlaced", "OrderCancelled".
     */
    @Column(name = "event_type")
    public String eventType;
}
```

**Key rules:**

- `subjectId` (from `LedgerEntry`) is the aggregate identifier. Set it to your domain object's UUID. Sequence numbering and the Merkle Mountain Range are scoped per `subjectId`.
- `@DiscriminatorValue` must be unique across all subclasses in the deployment.
- The class does not need `@PrePersist` — it inherits the one on `LedgerEntry` that sets `id` and `occurredAt`.

### Base fields available on every entry

| Field | Type | Description |
|---|---|---|
| `id` | UUID | Primary key (auto-assigned) |
| `subjectId` | UUID | Aggregate identifier — scopes sequence and hash chain |
| `sequenceNumber` | int | 1-based position in this subject's ledger |
| `entryType` | LedgerEntryType | COMMAND, EVENT, or ATTESTATION |
| `actorId` | String | Who performed this action — see [§ Actor identity](#actor-identity) |
| `actorType` | ActorType | HUMAN, AGENT, or SYSTEM |
| `actorRole` | String | Functional role (e.g. "Approver", "Resolver") |
| `correlationId` | String | OTel trace ID linking this entry to a distributed trace |
| `causedByEntryId` | UUID | FK to causal predecessor entry (cross-subject causality) |
| `digest` | String | Merkle leaf hash of this entry's canonical content (auto-set by `save()`) |
| `occurredAt` | Instant | When this entry was recorded (auto-set) |
| `supplementJson` | String | JSON snapshot of all attached supplements (auto-set by `attach()`) |

Optional fields are handled via supplements — see [§ Supplements](#supplements--optional-extensions) below.

| Supplement | Fields | Attach when |
|---|---|---|
| `ComplianceSupplement` | `planRef`, `rationale`, `evidence`, `detail`, `decisionContext`, `algorithmRef`, `confidenceScore`, `contestationUri`, `humanOverrideAvailable` | Recording decisions subject to GDPR Art.22 or EU AI Act Art.12 |
| `ProvenanceSupplement` | `sourceEntityId`, `sourceEntityType`, `sourceEntitySystem`, `agentConfigHash` | Subject is driven by an external workflow; or carries LLM agent configuration binding |

---

## Actor identity

### Human and system actors

For human actors, `actorId` is typically a user ID or username. For system actors
(schedulers, rule engines), `actorId` is a stable service identifier.

```java
entry.actorId   = currentUser.id();   // e.g. "alice"
entry.actorType = ActorType.HUMAN;
entry.actorRole = "Approver";
```

### LLM agents

LLM agents are stateless — each session starts fresh. Use a **versioned persona name**
as `actorId` so trust accumulates correctly across sessions (ADR 0004):

```
{model-family}:{persona}@{major}
```

| Segment | Description | Example |
|---|---|---|
| `model-family` | LLM family | `claude`, `gpt`, `gemini` |
| `persona` | Stable role name from system instructions | `tarkus-reviewer` |
| `@{major}` | Major version; bump when behaviour warrants a new trust baseline | `@v1` |

```java
entry.actorId   = "claude:tarkus-reviewer@v1";
entry.actorType = ActorType.AGENT;
entry.actorRole = "code-reviewer";  // broader functional classification
```

Bump the major version when the agent's system instructions or behaviour change
materially enough that accumulated trust should not carry over. Minor tuning and
bug fixes do not require a bump.

**Configuration binding (optional):** to detect configuration drift within a version,
attach a `ProvenanceSupplement` with `agentConfigHash` set to the SHA-256 hex of the
agent's configuration at session start (e.g. CLAUDE.md + system prompts). This is a
forensic audit field — it does not affect trust scoring.

```java
ProvenanceSupplement ps = new ProvenanceSupplement();
ps.agentConfigHash = sha256HexOf(claudeMd + systemPrompts);
entry.attach(ps);
```

---

## Step 3 — Write the Flyway migration

Number your subclass migration **after V1003** (the last quarkus-ledger migration). The subclass table has a `FOREIGN KEY ... REFERENCES ledger_entry (id)` — if it runs before V1000 creates `ledger_entry`, the migration fails. V1004 is the safe starting point for the first subclass table.

```sql
-- V1004__order_ledger_entry.sql
-- OrderLedgerEntry subclass table (JPA JOINED inheritance)
CREATE TABLE order_ledger_entry (
    id           UUID         NOT NULL,
    order_id     UUID         NOT NULL,
    command_type VARCHAR(100),
    event_type   VARCHAR(100),
    CONSTRAINT pk_order_ledger_entry PRIMARY KEY (id),
    CONSTRAINT fk_order_ledger_entry FOREIGN KEY (id) REFERENCES ledger_entry (id)
);

CREATE INDEX idx_ole_order_id ON order_ledger_entry (order_id);
```

The `FOREIGN KEY ... REFERENCES ledger_entry (id)` is what makes JOINED inheritance work. Hibernate inserts a row in both tables for each persisted `OrderLedgerEntry`.

---

## Step 4 — Create a typed repository

For clean, cast-free access to your subclass, create a standalone `@ApplicationScoped` repository that implements `LedgerEntryRepository`:

```java
import io.quarkiverse.ledger.runtime.model.*;
import io.quarkiverse.ledger.runtime.repository.LedgerEntryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class OrderLedgerEntryRepository implements LedgerEntryRepository {

    // -----------------------------------------------------------------------
    // Domain-typed convenience methods
    // -----------------------------------------------------------------------

    public List<OrderLedgerEntry> findByOrderId(UUID orderId) {
        return OrderLedgerEntry.list(
            "subjectId = ?1 ORDER BY sequenceNumber ASC", orderId);
    }

    public Optional<OrderLedgerEntry> findLatestByOrderId(UUID orderId) {
        return OrderLedgerEntry
            .find("subjectId = ?1 ORDER BY sequenceNumber DESC", orderId)
            .firstResultOptional();
    }

    // -----------------------------------------------------------------------
    // LedgerEntryRepository SPI — polymorphic base operations
    // -----------------------------------------------------------------------

    @Override
    public LedgerEntry save(LedgerEntry entry) {
        entry.persist();
        return entry;
    }

    @Override
    public List<LedgerEntry> findBySubjectId(UUID subjectId) {
        return LedgerEntry.list(
            "subjectId = ?1 ORDER BY sequenceNumber ASC", subjectId);
    }

    @Override
    public Optional<LedgerEntry> findLatestBySubjectId(UUID subjectId) {
        return LedgerEntry.find(
            "subjectId = ?1 ORDER BY sequenceNumber DESC", subjectId)
            .firstResultOptional();
    }

    @Override
    public Optional<LedgerEntry> findEntryById(UUID id) {
        return Optional.ofNullable(LedgerEntry.findById(id));
    }

    @Override
    public List<LedgerAttestation> findAttestationsByEntryId(UUID entryId) {
        return LedgerAttestation.list(
            "ledgerEntryId = ?1 ORDER BY occurredAt ASC", entryId);
    }

    @Override
    public LedgerAttestation saveAttestation(LedgerAttestation attestation) {
        attestation.persist();
        return attestation;
    }

    @Override
    public List<LedgerEntry> findAllEvents() {
        return LedgerEntry.find("entryType = ?1", LedgerEntryType.EVENT).list();
    }

    @Override
    public Map<UUID, List<LedgerAttestation>> findAttestationsForEntries(Set<UUID> entryIds) {
        if (entryIds.isEmpty()) return Collections.emptyMap();
        return LedgerAttestation.<LedgerAttestation>list("ledgerEntryId IN ?1", entryIds)
            .stream()
            .collect(Collectors.groupingBy(a -> a.ledgerEntryId));
    }
}
```

> **CDI note:** `JpaLedgerEntryRepository` (from quarkus-ledger) is annotated `@Alternative` so it does not conflict with your repository when both are on the classpath. No extra configuration needed.

---

## Step 5 — Write to the ledger on domain events

Inject your repository and `LedgerConfig` into the service that fires domain transitions:

```java
@ApplicationScoped
public class OrderService {

    @Inject OrderLedgerEntryRepository ledgerRepo;
    @Inject LedgerConfig ledgerConfig;

    @Transactional
    public void placeOrder(UUID orderId, String actor) {
        // ... business logic ...

        if (ledgerConfig.enabled()) {
            writeLedgerEntry(orderId, actor, "PlaceOrder", "OrderPlaced");
        }
    }

    private void writeLedgerEntry(UUID orderId, String actor,
                                  String commandType, String eventType) {
        Optional<OrderLedgerEntry> latest = ledgerRepo.findLatestByOrderId(orderId);
        int nextSeq = latest.map(e -> e.sequenceNumber + 1).orElse(1);

        OrderLedgerEntry entry = new OrderLedgerEntry();
        entry.subjectId      = orderId;
        entry.orderId        = orderId;
        entry.sequenceNumber = nextSeq;
        entry.entryType      = LedgerEntryType.EVENT;
        entry.commandType    = commandType;
        entry.eventType      = eventType;
        entry.actorId        = actor;
        entry.actorType      = ActorType.HUMAN;
        entry.actorRole      = "Initiator";
        // Set before repo.save() — @PrePersist runs too late for leaf hash computation
        entry.occurredAt     = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        if (ledgerConfig.decisionContext().enabled()) {
            final ComplianceSupplement cs = new ComplianceSupplement();
            cs.decisionContext = buildDecisionContext(orderId);
            entry.attach(cs);
        }

        // Merkle leaf hash and frontier update handled automatically by save()
        ledgerRepo.save(entry);
    }

    private String buildDecisionContext(UUID orderId) {
        // Return a JSON snapshot of the order state at this moment
        Order order = Order.findById(orderId);
        return String.format("{\"status\":\"%s\",\"total\":%s}",
            order.status, order.total);
    }
}
```

> **Transaction boundary:** The ledger write must happen in the same transaction as the domain state change. If the transaction rolls back, no ledger entry is written — ensuring consistency.

> **occurredAt timing:** Always set `occurredAt` explicitly with millisecond precision *before* calling `repo.save()`. The `@PrePersist` hook runs too late for the leaf hash computation, and the database truncates to milliseconds anyway.

---

## Step 6 — Query the audit trail

```java
// Full ledger for one order, in sequence order
List<OrderLedgerEntry> entries = ledgerRepo.findByOrderId(orderId);

// Verify integrity via Merkle Mountain Range — injected CDI bean
@Inject LedgerVerificationService verificationService;
// ...
boolean intact = verificationService.verify(orderId);

// Load attestations for a specific entry
List<LedgerAttestation> attestations =
    ledgerRepo.findAttestationsByEntryId(entries.get(0).id);

// Post a peer attestation
LedgerAttestation stamp = new LedgerAttestation();
stamp.ledgerEntryId = entries.get(0).id;
stamp.subjectId     = orderId;
stamp.attestorId    = "compliance-agent";
stamp.attestorType  = ActorType.AGENT;
stamp.verdict       = AttestationVerdict.SOUND;
stamp.confidence    = 0.95;
ledgerRepo.saveAttestation(stamp);
```

---

## Step 7 — Configuration reference

Add to `application.properties`:

```properties
# Master switch (default: true)
quarkus.ledger.enabled=true

# Merkle leaf hash computation (RFC 9162 domain separation) — enables tamper detection (default: true)
quarkus.ledger.hash-chain.enabled=true

# Decision context snapshots — required for GDPR Art.22 / EU AI Act Art.12 (default: true)
quarkus.ledger.decision-context.enabled=true

# Structured evidence fields — off by default (enable when callers supply evidence)
quarkus.ledger.evidence.enabled=false

# Peer attestation API (default: true)
quarkus.ledger.attestations.enabled=true

# Nightly EigenTrust trust score computation (default: false)
# Enable only once history has accumulated — early scores are unreliable
quarkus.ledger.trust-score.enabled=false

# Exponential decay half-life for score weighting (default: 90 days)
quarkus.ledger.trust-score.decay-half-life-days=90

# Trust-score routing signals via CDI events (default: false)
quarkus.ledger.trust-score.routing-enabled=false

# Merkle tree external publishing (opt-in) — post Ed25519-signed tlog-checkpoint on each write
# quarkus.ledger.merkle.publish.url=https://your-checkpoint-log/
# quarkus.ledger.merkle.publish.private-key=/path/to/ed25519-key.pem
```

---

## Provenance — recording which external system created a subject

When an external system (a workflow engine, orchestrator, or messaging layer) creates the domain object that the ledger is tracking, record its identity on the first entry:

```java
// After creating the first ledger entry (sequenceNumber = 1):
final ProvenanceSupplement ps = new ProvenanceSupplement();
ps.sourceEntityId     = "workflow-instance-abc";
ps.sourceEntityType   = "Flow:WorkflowInstance";
ps.sourceEntitySystem = "quarkus-flow";
entry.attach(ps);
```

This enables cross-system audit queries: given a workflow instance ID, find all domain objects it created and their full audit trails.

---

## Using trust scoring (Bayesian Beta)

When `trust-score.enabled=true`, a nightly `@Scheduled` job (`TrustScoreJob`) computes trust scores for all actors who have attestations. Scores are written to `actor_trust_score`.

The algorithm — Bayesian Beta accumulation:
- **`α` (alpha):** incremented for positive verdicts (SOUND, ENDORSED), recency-weighted
- **`β` (beta):** incremented for negative verdicts (FLAGGED, CHALLENGED), recency-weighted
- **Recency weight:** `2^(-(ageInDays / halfLifeDays))` — older attestations decay
- **Score:** `α / (α + β)`, clamped to [0.0, 1.0]. Prior Beta(1,1) → score 0.5 with no history

Enable only once attestation history has accumulated — early scores with sparse data are unreliable.

Query scores at any time:

```java
@Inject ActorTrustScoreRepository trustRepo;

Optional<ActorTrustScore> score = trustRepo.findByActorId("alice");
score.ifPresent(s -> {
    System.out.printf("Alice trust: %.2f (based on %d decisions)%n",
        s.trustScore, s.decisionCount);
});
```

To invoke the job directly in tests (scheduler is typically disabled in test profiles):

```java
@Inject TrustScoreJob trustScoreJob;

trustScoreJob.runComputation(); // @Transactional, safe to call directly
```

---

## Merkle tree verification

`LedgerVerificationService` (auto-activated CDI bean) provides tamper detection via the
Merkle Mountain Range built incrementally on each write:

```java
@Inject LedgerVerificationService verificationService;

// Verify all entries for a subject — O(N log N)
boolean intact = verificationService.verify(subjectId);
// intact == false → at least one entry has been modified since writing

// Get the current Merkle root for external anchoring
String root = verificationService.treeRoot(subjectId);

// Get an O(log N) inclusion proof for a specific entry
InclusionProof proof = verificationService.inclusionProof(entryId);
```

Each entry's `digest` is a Merkle leaf hash (RFC 9162 domain separation) computed over
base-class fields only:
`subjectId|seqNum|entryType|actorId|actorRole|occurredAt`

Subclass-specific fields (like `commandType`, `eventType`) and supplement fields are
intentionally excluded — the hash covers provenance and timing, not domain labels or
compliance enrichment.

---

## Supplements — Optional Extensions

Supplements add cross-cutting fields to any ledger entry without polluting the core
entity. See `docs/DESIGN.md` § Supplements for the full reference.

### Quick start — GDPR Art.22 compliance

```java
// In your capture service, after building the entry:
ComplianceSupplement cs = new ComplianceSupplement();
cs.algorithmRef       = "my-model-v2";
cs.confidenceScore    = 0.87;
cs.contestationUri    = "https://yourapp.com/decisions/" + entry.id + "/challenge";
cs.humanOverrideAvailable = true;
cs.decisionContext    = objectMapper.writeValueAsString(inputSnapshot);
entry.attach(cs);  // persists with entry; no separate persist() call needed
```

The `supplementJson` field is populated automatically by `attach()` — no extra
step required for single-entry reads.

### Runnable example

`examples/art22-decision-snapshot/` — a full Quarkus app demonstrating a GDPR
Art.22 compliant AI decision service. See its `README.md` for regulatory context.
