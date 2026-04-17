# Quarkus Ledger — Session Handover
**Date:** 2026-04-17

## Current State

- **Tests:** 92 runtime + 12 order-processing + 3 art22 + 3 art12 = 110 total, all passing
- **Open issues:** #11 (Merkle tree upgrade) — only open issue
- **Version:** 1.0.0-SNAPSHOT
- **Flyway:** V1000–V1003 base (V1000/V1002 now correctly committed to git)

## What Landed This Session

**Research + compliance sprint (issues #7, #8, #9, #10 — all closed):**
- LedgerSupplement architecture — 3 supplement types, supplements down to 2 this session
- Forgiveness mechanism — two-parameter (recency × frequency), ADR 0001 written
- EU AI Act Art.12 — retention job (archive-then-delete), 3 audit query methods
- Causality query API — `causedByEntryId` + `correlationId` moved to `LedgerEntry` core; `ObservabilitySupplement` deleted; `findCausedBy(UUID)` SPI method

**Key architectural decision this session:**
Core vs supplement test — "is this field relevant to every consumer, every entry, every time?" If yes → core. `correlationId` (OTel) and `causedByEntryId` (causality) both passed → moved to core → ObservabilitySupplement had no fields left → deleted.

**Critical bug fixed:** V1000, V1001, V1002 Flyway SQL files were never committed to git. Now committed.

## Immediate Next Step

Start on **issue #11 — Merkle tree upgrade**.

Brainstorming not yet done for #11. Begin with brainstorm → spec → plan → subagent-driven execution. The spec is at `docs/superpowers/specs/2026-04-17-causality-query-api-design.md` for reference on prior spec format.

Issue #11 context: linear hash chain requires O(N) to verify any single entry. Merkle tree gives O(log N) inclusion proofs. Closes Axiom 4 (Verifiability). Architecture play before Quarkiverse submission. See `docs/RESEARCH.md` item #8 for research sources (RFC 9162, Russ Cox's transparent logs essay, Sunlight).

## References

| What | Path |
|---|---|
| Design doc | `docs/DESIGN.md` |
| Auditability gap analysis (Axiom 4 still open) | `docs/AUDITABILITY.md` |
| Research priority matrix | `docs/RESEARCH.md` |
| Issue #11 | https://github.com/mdproctor/quarkus-ledger/issues/11 |
| ADR 0001 (forgiveness severity exclusion) | `adr/0001-forgiveness-mechanism-omits-severity-dimension.md` |
| Latest blog entry | `blog/2026-04-17-mdp02-two-fields-in-the-wrong-place.md` |
| Supplement spec | `docs/superpowers/specs/2026-04-17-causality-query-api-design.md` |
