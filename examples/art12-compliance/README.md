# Example: EU AI Act Article 12 Compliance

This example demonstrates how to use `quarkus-ledger` to satisfy EU AI Act Article 12
(Record-keeping) requirements for high-risk AI systems.

## What is EU AI Act Article 12?

Article 12 requires high-risk AI systems to:
1. **Automatically record events** throughout the system lifecycle
2. **Retain operational logs** for at least 6 months
3. **Ensure reconstructability** — an auditor can retrieve all AI decisions for any actor or time range on demand

Enforcement begins **2 August 2026**. Penalties: up to €15M or 3% of global turnover.

## How this example satisfies Article 12

| Art.12 requirement | Ledger capability |
|---|---|
| Automatic event recording | `LedgerEntry` persisted on every AI decision |
| Tamper evidence | SHA-256 hash chain (`previousHash` + `digest`) |
| 6-month retention | `quarkus.ledger.retention.operational-days=180` |
| Reconstructability by actor | `LedgerEntryRepository.findByActorId(actorId, from, to)` |
| Reconstructability by time window | `LedgerEntryRepository.findByTimeRange(from, to)` |
| Decision context (Art.22) | `ComplianceSupplement` — algorithmRef, confidenceScore, contestationUri |

## Running the example

```bash
cd examples/art12-compliance
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn quarkus:dev
```

```bash
# Record an AI decision
curl -X POST "http://localhost:8080/decisions?actorId=agent-1&category=credit-risk&algorithm=model-v3&confidence=0.92"

# Audit all decisions by a specific agent (Art.12 reconstructability)
curl "http://localhost:8080/decisions/audit?actorId=agent-1&from=2026-01-01T00:00:00Z&to=2099-12-31T23:59:59Z"

# Audit all decisions in a time window
curl "http://localhost:8080/decisions/range?from=2026-01-01T00:00:00Z&to=2026-12-31T23:59:59Z"
```

## Running the tests

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test
```
