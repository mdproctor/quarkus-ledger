# Quarkus Ledger

A Quarkus extension providing a domain-agnostic immutable audit ledger for any Quarkus application.

Add `quarkus-ledger` to your app and get:

- **Immutable append-only audit log** — every domain transition recorded with sequence numbering
- **SHA-256 hash chain** — Certificate Transparency tamper evidence, verifiable offline
- **Peer attestations** — actors stamp verdicts (SOUND / FLAGGED / ENDORSED / CHALLENGED) with confidence scores
- **EigenTrust reputation** — nightly score computation from attestation history, exponential decay weighting
- **Decision context snapshots** — point-in-time state capture for GDPR Article 22 / EU AI Act Article 12
- **Provenance tracking** — record which external system originated a domain object

The base `LedgerEntry` entity uses JPA JOINED inheritance. You extend it with a domain-specific subclass that adds your own fields. The base tables are always present; your subclass table joins on `id`.

**Reference implementations:**
- [quarkus-tarkus](https://github.com/mdproctor/quarkus-tarkus) — `WorkItemLedgerEntry` for task lifecycle audit
- [quarkus-qhorus](https://github.com/mdproctor/quarkus-qhorus) — `AgentMessageLedgerEntry` for AI agent telemetry

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

The extension adds `ledger` to your Quarkus feature list at startup and creates three base tables via Flyway (migrations V1000–V1001):

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
| [Integration Guide](docs/integration-guide.md) | Step-by-step: subclass, migration, repository, capture, queries, configuration |
| [Examples](docs/examples.md) | Complete worked example — order processing domain |

---

## Configuration

All keys are under `quarkus.ledger`:

| Key | Default | Description |
|---|---|---|
| `quarkus.ledger.enabled` | `true` | Master switch |
| `quarkus.ledger.hash-chain.enabled` | `true` | SHA-256 tamper evidence |
| `quarkus.ledger.decision-context.enabled` | `true` | State snapshot on each entry |
| `quarkus.ledger.evidence.enabled` | `false` | Structured evidence fields |
| `quarkus.ledger.attestations.enabled` | `true` | Peer attestation API |
| `quarkus.ledger.trust-score.enabled` | `false` | Nightly EigenTrust computation |
| `quarkus.ledger.trust-score.decay-half-life-days` | `90` | Recency decay half-life |
| `quarkus.ledger.trust-score.routing-enabled` | `false` | Trust-score routing signals |

---

## License

Apache 2.0
