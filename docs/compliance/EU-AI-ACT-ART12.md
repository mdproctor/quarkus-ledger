# EU AI Act Article 12 — Compliance Mapping

**Regulation:** EU AI Act (Regulation 2024/1689), Article 12 — Record-keeping
**Enforcement date:** 2 August 2026 (Annex III high-risk AI systems)
**Penalties:** Up to €15M or 3% of global annual turnover

This document maps each Article 12 obligation to the specific `casehub-ledger`
capability that satisfies it, for use in conformity assessments.

---

## Requirement → Feature Mapping

| Art.12 Requirement | Ledger Feature | Config / API |
|---|---|---|
| Automatically record events throughout the AI system lifecycle | `LedgerEntry` persisted on every domain transition | `quarkus.ledger.enabled=true` (default) |
| Records must be tamper-evident | SHA-256 hash chain — any modification breaks the chain | `quarkus.ledger.hash-chain.enabled=true` (default) |
| Retain operational logs for at least 6 months | `LedgerRetentionJob` enforces minimum retention window | `quarkus.ledger.retention.operational-days=180` |
| Archive before deletion | `ledger_entry_archive` table — complete JSON snapshot | `quarkus.ledger.retention.archive-before-delete=true` (default) |
| Full reconstructability of AI decisions on demand — by actor | `LedgerEntryRepository.findByActorId(actorId, from, to)` | Auditor REST endpoint |
| Full reconstructability by time window | `LedgerEntryRepository.findByTimeRange(from, to)` | Auditor REST endpoint |
| Reconstructability by role | `LedgerEntryRepository.findByActorRole(role, from, to)` | Auditor REST endpoint |
| Decision context: logic used, inputs | `ComplianceSupplement.decisionContext` (JSON snapshot) | `entry.attach(cs)` |
| Decision context: algorithm/model reference | `ComplianceSupplement.algorithmRef` | `cs.algorithmRef = "model-v3"` |
| Decision context: confidence / significance | `ComplianceSupplement.confidenceScore` | `cs.confidenceScore = 0.92` |
| Right to contest (GDPR Art.22 link) | `ComplianceSupplement.contestationUri` | `cs.contestationUri = "https://..."` |
| Human oversight availability | `ComplianceSupplement.humanOverrideAvailable` | `cs.humanOverrideAvailable = true` |

---

## Minimum Configuration for Art.12 Compliance

```properties
# application.properties
quarkus.ledger.enabled=true
quarkus.ledger.hash-chain.enabled=true
quarkus.ledger.retention.enabled=true
quarkus.ledger.retention.operational-days=180
quarkus.ledger.retention.archive-before-delete=true
```

---

## What Art.12 Does NOT Require From the Ledger

The ledger provides the **record-keeping substrate**. The following are
consumer responsibilities, not part of the base extension:

- The HTTP endpoint that exposes audit query results to regulators
- The conformity assessment documentation (Article 18 — separate from Art.12)
- Risk classification of whether your specific system falls under Annex III

---

## Runnable Example

`examples/art12-compliance/` — a standalone Quarkus application demonstrating all
of the above in a runnable, testable form. See its `README.md` for curl examples.

---

## References

- [EU AI Act Article 12 — official text](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-12/)
- [Art.12 compliance checklist](https://www.isms.online/iso-42001/eu-ai-act/article-12/)
- `docs/AUDITABILITY.md` — 8-axiom self-assessment
