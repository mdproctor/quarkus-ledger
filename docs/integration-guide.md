# Integration Guide

This guide walks through everything needed to add `quarkus-ledger` to a Quarkus application or extension.

---

## How it works

`quarkus-ledger` uses JPA JOINED inheritance. The base `ledger_entry` table holds all domain-agnostic fields (actor, sequence, hash chain, provenance, decision context). Your domain subclass adds a sibling table that joins on `id`.

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

Flyway picks up `quarkus-ledger`'s migrations (V1000, V1001) automatically from the classpath. Your own migrations start from a lower number (e.g. V1–V9) or a distinct high range (e.g. V2000+). Do not use V1000–V1001.

> **Critical:** your subclass migration must run **after** quarkus-ledger's V1000 (which creates `ledger_entry`), because the subclass table has a `FOREIGN KEY ... REFERENCES ledger_entry (id)`. If you number your subclass migration V5 and the base schema is V1000, Flyway will try to create the FK before the parent table exists and fail with `Table "LEDGER_ENTRY" not found`.
>
> Safe numbering: use V1–V999 for your domain tables and **V1002+ for any subclass join tables** (V1000 = ledger_entry base, V1001 = actor_trust_score). See the [example](../examples/order-processing/) where `V1002__order_ledger_entry.sql` follows this rule.

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

- `subjectId` (from `LedgerEntry`) is the aggregate identifier. Set it to your domain object's UUID. Sequence numbering and hash chaining are scoped per `subjectId`.
- `@DiscriminatorValue` must be unique across all subclasses in the deployment.
- The class does not need `@PrePersist` — it inherits the one on `LedgerEntry` that sets `id` and `occurredAt`.

### Base fields available on every entry

| Field | Type | Description |
|---|---|---|
| `id` | UUID | Primary key (auto-assigned) |
| `subjectId` | UUID | Aggregate identifier — scopes sequence and hash chain |
| `sequenceNumber` | int | 1-based position in this subject's ledger |
| `entryType` | LedgerEntryType | COMMAND, EVENT, or ATTESTATION |
| `actorId` | String | Who performed this action |
| `actorType` | ActorType | HUMAN, AGENT, or SYSTEM |
| `actorRole` | String | Functional role (e.g. "Approver", "Resolver") |
| `previousHash` | String | SHA-256 digest of the preceding entry |
| `digest` | String | SHA-256 digest of this entry's canonical content |
| `occurredAt` | Instant | When this entry was recorded (auto-set) |
| `supplementJson` | String | JSON snapshot of all attached supplements (auto-set by `attach()`) |

Optional fields are handled via supplements — see [§ Supplements](#supplements--optional-extensions) below.

| Supplement | Fields | Attach when |
|---|---|---|
| `ComplianceSupplement` | `planRef`, `rationale`, `evidence`, `detail`, `decisionContext`, `algorithmRef`, `confidenceScore`, `contestationUri`, `humanOverrideAvailable` | Recording decisions subject to GDPR Art.22 or EU AI Act Art.12 |
| `ProvenanceSupplement` | `sourceEntityId`, `sourceEntityType`, `sourceEntitySystem` | Subject is driven by an external workflow |
| `ObservabilitySupplement` | `correlationId`, `causedByEntryId` | Linking to OTel traces or cross-system causality |

---

## Step 3 — Write the Flyway migration

Number your subclass migration **after V1001** (the last quarkus-ledger migration). The subclass table has a `FOREIGN KEY ... REFERENCES ledger_entry (id)` — if it runs before V1000 creates `ledger_entry`, the migration fails. V1002 is the safe starting point for the first subclass table.

```sql
-- V1002__order_ledger_entry.sql
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
    public Optional<LedgerEntry> findById(UUID id) {
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
        // Sequence and previous hash — scoped per order
        Optional<OrderLedgerEntry> latest = ledgerRepo.findLatestByOrderId(orderId);
        int nextSeq = latest.map(e -> e.sequenceNumber + 1).orElse(1);
        String previousHash = latest.map(e -> e.digest).orElse(null);

        OrderLedgerEntry entry = new OrderLedgerEntry();
        entry.subjectId     = orderId;
        entry.orderId       = orderId;
        entry.sequenceNumber = nextSeq;
        entry.entryType     = LedgerEntryType.EVENT;
        entry.commandType   = commandType;
        entry.eventType     = eventType;
        entry.actorId       = actor;
        entry.actorType     = ActorType.HUMAN;
        entry.actorRole     = "Initiator";
        // Set occurredAt before hash computation — @PrePersist sets it too late
        entry.occurredAt    = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        if (ledgerConfig.decisionContext().enabled()) {
            final ComplianceSupplement cs = new ComplianceSupplement();
            cs.decisionContext = buildDecisionContext(orderId);
            entry.attach(cs);
        }

        if (ledgerConfig.hashChain().enabled()) {
            entry.previousHash = previousHash;
            entry.digest = LedgerHashChain.compute(previousHash, entry);
        }

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

> **occurredAt timing:** Always set `occurredAt` explicitly with millisecond precision *before* calling `LedgerHashChain.compute()`. The `@PrePersist` hook runs too late for the hash computation, and the database truncates to milliseconds anyway.

---

## Step 6 — Query the audit trail

```java
// Full ledger for one order, in sequence order
List<OrderLedgerEntry> entries = ledgerRepo.findByOrderId(orderId);

// Verify the hash chain has not been tampered with
boolean intact = LedgerHashChain.verify(entries);

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

# SHA-256 hash chain — Certificate Transparency tamper detection (default: true)
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

## Using EigenTrust reputation

When `trust-score.enabled=true`, a nightly `@Scheduled` job (`TrustScoreJob`) computes trust scores for all actors who have EVENT-type entries. Scores are written to `actor_trust_score`.

The algorithm:
- **Decision score:** 1.0 (no negative attestations) / 0.5 (mixed) / 0.0 (majority negative)
- **Recency weight:** `2^(-(ageInDays / halfLifeDays))` — older decisions decay
- **Trust score:** weighted average, clamped to [0.0, 1.0], neutral prior 0.5

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

## Hash chain verification

Verify chain integrity at any time — no runtime dependency on the database format:

```java
List<OrderLedgerEntry> entries = ledgerRepo.findByOrderId(orderId);
boolean intact = LedgerHashChain.verify(entries);
// intact == false → at least one entry has been modified since writing
```

The canonical form hashes only base-class fields:
`subjectId|seqNum|entryType|actorId|actorRole|occurredAt`

Subclass-specific fields (like `commandType`, `eventType`) and supplement fields are
intentionally excluded — the chain covers provenance and timing, not domain labels or
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
