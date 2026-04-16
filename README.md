# Quarkus Ledger

A Quarkus extension providing a domain-agnostic immutable audit ledger for any Quarkus application.

Add `quarkus-ledger` to your app and get:

- **Immutable append-only audit log** — every domain transition recorded with sequence numbering
- **SHA-256 hash chain** — Certificate Transparency tamper evidence, verifiable offline
- **Peer attestations** — actors stamp verdicts (SOUND / FLAGGED / ENDORSED / CHALLENGED) with confidence scores
- **EigenTrust reputation** — nightly score computation from attestation history, exponential decay weighting
- **Supplements** — optional cross-cutting extensions (`ComplianceSupplement`, `ProvenanceSupplement`, `ObservabilitySupplement`) attached via `entry.attach(supplement)`, lazily loaded, zero overhead when unused
- **GDPR Art.22 compliance** — `ComplianceSupplement` carries `algorithmRef`, `confidenceScore`, `contestationUri`, `humanOverrideAvailable`, and `decisionContext` for automated decision auditability

The base `LedgerEntry` entity uses JPA JOINED inheritance. You extend it with a domain-specific subclass that adds your own fields. The base tables are always present; your subclass table joins on `id`.

**Reference implementations:**
- [quarkus-tarkus](https://github.com/mdproctor/quarkus-tarkus) — `WorkItemLedgerEntry` for task lifecycle audit
- [quarkus-qhorus](https://github.com/mdproctor/quarkus-qhorus) — `AgentMessageLedgerEntry` for AI agent telemetry

---

## Ecosystem Context

`quarkus-ledger` is the shared audit foundation for the Quarkus Native AI Ecosystem:

```
quarkus-ledger        (audit/provenance — this project)
    ↑         ↑         ↑
 tarkus    qhorus    casehub    (each adds its own LedgerEntry subclass)
```

It was extracted from `quarkus-tarkus` to avoid duplication across projects.
The extension is intentionally thin — REST endpoints, MCP tools, and CDI capture
services are the consumer's responsibility. Each domain knows its own path structure,
auth model, and event system. See [`docs/DESIGN.md`](docs/DESIGN.md) for the full
architecture and design rationale.

---

## Requirements

- Java 21+
- Quarkus 3.x
- Hibernate ORM with Panache
- Flyway

---

## Add the Dependency

```xml
<dependency>
  <groupId>io.quarkiverse.ledger</groupId>
  <artifactId>quarkus-ledger</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Also add the deployment artifact to your deployment module if you are building a Quarkus extension:

```xml
<dependency>
  <groupId>io.quarkiverse.ledger</groupId>
  <artifactId>quarkus-ledger-deployment</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

The extension adds `ledger` to your Quarkus feature list at startup and creates three base tables via Flyway (migrations V1000–V1002), plus supplement tables when supplements are used:

| Table | Purpose |
|---|---|
| `ledger_entry` | Base audit record (all domains) |
| `ledger_attestation` | Peer verdicts on entries |
| `actor_trust_score` | Nightly-computed EigenTrust scores |

---

## Quick Start

**1. Create your domain subclass** (5 minutes):

```java
@Entity
@Table(name = "order_ledger_entry")
@DiscriminatorValue("ORDER")
public class OrderLedgerEntry extends LedgerEntry {

    @Column(name = "order_id", nullable = false)
    public UUID orderId;

    @Column(name = "transition_type")
    public String transitionType; // e.g. "PlaceOrder", "ShipOrder"
}
```

**2. Add a Flyway migration** for the subclass table:

```sql
CREATE TABLE order_ledger_entry (
    id              UUID         NOT NULL,
    order_id        UUID         NOT NULL,
    transition_type VARCHAR(100),
    CONSTRAINT pk_order_ledger_entry PRIMARY KEY (id),
    CONSTRAINT fk_order_ledger_entry FOREIGN KEY (id) REFERENCES ledger_entry (id)
);
```

**3. Write to the ledger** when a domain event occurs:

```java
final OrderLedgerEntry entry = new OrderLedgerEntry();
entry.subjectId = order.id;        // aggregate identifier — scopes sequence + hash chain
entry.orderId   = order.id;
entry.sequenceNumber = nextSeq(order.id);
entry.entryType = LedgerEntryType.EVENT;
entry.transitionType = "PlaceOrder";
entry.actorId   = currentUser;
entry.actorType = ActorType.HUMAN;
entry.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

if (config.hashChain().enabled()) {
    entry.previousHash = previousDigest(order.id);
    entry.digest = LedgerHashChain.compute(entry.previousHash, entry);
}

entry.persist();
```

**4. Verify the chain** at any time:

```java
List<OrderLedgerEntry> entries = OrderLedgerEntry
    .list("subjectId = ?1 ORDER BY sequenceNumber ASC", orderId);

boolean intact = LedgerHashChain.verify(entries); // true if untampered
```

---

## Documentation

| Doc | Contents |
|---|---|
| [Integration Guide](docs/integration-guide.md) | Step-by-step: subclass, migration, repository, capture, supplements, queries, configuration |
| [Examples](docs/examples.md) | Complete worked example — order processing domain |
| [Runnable Example — Order Processing](examples/order-processing/) | `mvn quarkus:dev` — order lifecycle with ledger, hash chain, attestations |
| [Runnable Example — GDPR Art.22](examples/art22-decision-snapshot/) | `mvn quarkus:dev` — AI decision service with full Art.22 compliance supplement |
| [Auditability Assessment](docs/AUDITABILITY.md) | 8-axiom self-assessment against ACM FAIR 2025 framework |

---

## Configuration

All keys are under `quarkus.ledger`:

| Key | Default | Description |
|---|---|---|
| `quarkus.ledger.enabled` | `true` | Master switch |
| `quarkus.ledger.hash-chain.enabled` | `true` | SHA-256 tamper evidence |
| `quarkus.ledger.decision-context.enabled` | `true` | Gate for populating `ComplianceSupplement.decisionContext` |
| `quarkus.ledger.evidence.enabled` | `false` | Gate for populating `ComplianceSupplement.evidence` |
| `quarkus.ledger.attestations.enabled` | `true` | Peer attestation API |
| `quarkus.ledger.trust-score.enabled` | `false` | Nightly EigenTrust computation |
| `quarkus.ledger.trust-score.decay-half-life-days` | `90` | Recency decay half-life |
| `quarkus.ledger.trust-score.routing-enabled` | `false` | Trust-score routing signals |

---

## License

Apache 2.0
