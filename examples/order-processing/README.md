# Order Processing Example

A runnable Quarkus application demonstrating `quarkus-ledger` integration. Tracks an order lifecycle with:

- Immutable ledger entries on every state transition (place → ship → deliver / cancel)
- SHA-256 hash chain tamper evidence per order
- Decision context snapshots (order status at transition time)
- Peer attestation API

---

## Prerequisites

`quarkus-ledger` must be installed to the local Maven repository first:

```bash
cd ../../   # quarkus-ledger root
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install -DskipTests
```

---

## Run in dev mode

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn quarkus:dev
```

Three sample orders are seeded on startup. Try these commands:

```bash
# List all orders (includes the 3 seeded ones)
curl -s http://localhost:8080/orders | jq .

# Place a new order
ORDER=$(curl -s -X POST http://localhost:8080/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"alice","total":49.99}' | jq -r .id)

echo "Order ID: $ORDER"

# Ship it
curl -s -X PUT "http://localhost:8080/orders/$ORDER/ship?actor=warehouse" | jq .status

# View the full audit trail
curl -s "http://localhost:8080/orders/$ORDER/ledger" | jq '[.[] | {seq: .sequenceNumber, cmd: .commandType, event: .eventType, actor: .actorId, digest: (.digest[:8] + "...")}]'

# Verify hash chain integrity
curl -s "http://localhost:8080/orders/$ORDER/ledger/verify" | jq .

# Post a peer attestation on the first entry
ENTRY=$(curl -s "http://localhost:8080/orders/$ORDER/ledger" | jq -r '.[0].id')
curl -s -X POST "http://localhost:8080/orders/$ORDER/ledger/$ENTRY/attestations" \
  -H 'Content-Type: application/json' \
  -d '{"attestorId":"compliance-bot","attestorType":"AGENT","verdict":"SOUND","confidence":0.95}'

# See the attestation embedded in the ledger
curl -s "http://localhost:8080/orders/$ORDER/ledger" | jq '.[0].attestations'
```

---

## Run tests

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test
```

10 integration tests cover: order creation, ledger entry creation, hash chain linking,
chain verification, supplementJson with decision context, cancel with rationale,
peer attestations, and supplement-specific assertions.

---

## Flyway migration notes

| Version | File | What |
|---|---|---|
| V1 | `V1__order_schema.sql` | `orders` table |
| V1000 | from `quarkus-ledger` jar | `ledger_entry` + `ledger_attestation` base tables |
| V1001 | from `quarkus-ledger` jar | `actor_trust_score` table |
| V1002 | from `quarkus-ledger` jar | supplement tables (`ledger_supplement` + subclass tables) |
| V1003 | `V1003__order_ledger_entry.sql` | `order_ledger_entry` join table |

V1003 must be > V1002 so the supplement infrastructure and base `ledger_entry` table both exist before the FK constraint.
