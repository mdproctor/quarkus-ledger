# Quarkus Ledger — Session Handover
**Date:** 2026-04-16

## Current State

- **Tests:** 62 total — 42 runtime unit/IT + 10 order-processing IT + 3 art22 IT, all passing
- **Examples:** `examples/order-processing/` (10 IT), `examples/art22-decision-snapshot/` (3 IT)
- **Open issues:** #8 (forgiveness mechanism), #9 (EU AI Act Art.12) — epic #6 still open
- **Version:** 1.0.0-SNAPSHOT
- **Flyway:** V1000–V1002 base schema (V1002 = supplement tables)

## What Landed This Session

**LedgerSupplement architecture (Closes #7):**
- `LedgerEntry` slimmed to 10 core fields — 10 optional fields moved to supplement entities
- `ComplianceSupplement` — GDPR Art.22 (algorithmRef, confidenceScore, contestationUri,
  humanOverrideAvailable, decisionContext) + governance (planRef, rationale, evidence, detail)
- `ProvenanceSupplement` — sourceEntityId/Type/System
- `ObservabilitySupplement` — correlationId, causedByEntryId
- `LedgerSupplementSerializer` — JSON serialiser for `supplementJson` column
- `LedgerEntry.attach()` + `refreshSupplementJson()` + typed accessors
- Hash chain canonical form: `planRef` removed (now 6 fields)
- Flyway V1002: supplement tables; consumer subclass migrations must use V1003+
- New example: `examples/art22-decision-snapshot/` — GDPR Art.22 runnable app
- Full doc pass: DESIGN.md supplement chapter, AUDITABILITY.md Axiom 8 ✅, integration guide,
  CLAUDE.md, README, examples.md all updated

## Immediate Next Step

Issues #8 and #9 are next in epic #6:
- **#8** — Forgiveness mechanism in EigenTrust (`TrustScoreComputer`)
- **#9** — EU AI Act Art.12 compliance surface (retention config + audit query API)

Both are independent. Start with #8 (smaller, self-contained algorithm change).

## References

| What | Path |
|---|---|
| Design doc | `docs/DESIGN.md` |
| Auditability gap analysis | `docs/AUDITABILITY.md` |
| Research priority matrix | `docs/RESEARCH.md` |
| Integration guide | `docs/integration-guide.md` |
| Worked examples | `docs/examples.md` |
| Order processing example | `examples/order-processing/` |
| GDPR Art.22 example | `examples/art22-decision-snapshot/` |
| Supplement spec | `docs/superpowers/specs/2026-04-16-ledger-supplement-architecture-design.md` |
| Epic #6 | https://github.com/mdproctor/quarkus-ledger/issues/6 |
| Issue #8 (forgiveness) | https://github.com/mdproctor/quarkus-ledger/issues/8 |
| Issue #9 (Art.12) | https://github.com/mdproctor/quarkus-ledger/issues/9 |
