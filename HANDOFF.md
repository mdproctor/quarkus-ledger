# Quarkus Ledger — Session Handover
**Date:** 2026-04-29

## Current State

`casehubio/ledger`, version `0.2-SNAPSHOT`. Clean working tree (`.claude/settings.local.json` modified locally — not a concern). SNAPSHOT installed and pushed to origin.

## What Landed This Session

**Confidence & trust scoring fixes:**
- `confidence` weighted in Bayesian Beta: `weight = recencyWeight × clamp(confidence, 0,1)`; `DEFAULT 1.0` on `ledger_attestation.confidence`; `overturnedCount` gated on `weight > 0` (Closes #69)

**Prerequisite refactors:**
- #67: `LedgerEntryEnricher` SPI + `TraceIdEnricher` + `LedgerTraceListener` pipeline runner (Closes #67)
- #68: `ActorTrustScore` discriminator model — UUID PK, `score_type` GLOBAL|CAPABILITY|DIMENSION, `scope_key` nullable, `UNIQUE NULLS NOT DISTINCT` (Closes #68)

**Group A — all shipped:**
- #55: `DecayFunction` SPI + `ExponentialDecayFunction` with valence multiplier (FLAGGED decays 0.5× slower, configurable); `TrustScoreComputer` delegates decay (Closes #55)
- #54: `TrustGateService` CDI bean — `meetsThreshold(actorId, minTrust)`, capability overload Phase 1 falls back to global (Closes #54)
- #53: `ActorTypeResolver.resolve()` in casehub-work, casehub-qhorus, casehub-engine, claudony — all local `deriveActorType()` removed (Closes #53)

**Process fix:** Important review findings now must reach the user or be fixed — never auto-dismissed by the controller (saved to memory).

## Open Issues from Cross-Repo Audit (#72)

**claudony** — both bugs self-fixed by the claudony Claude session (confirmed via linter note):
- ✅ Silent exception swallowing: `try/catch` removed, exceptions now propagate
- ✅ `nextSequenceNumber()` race: replaced with JPQL query ordered by `sequenceNumber DESC`

**casehub-work** — still outstanding, prompts sent to that Claude session:
- JSON built with `String.format()` in `buildDecisionContext()` — no escaping
- Missing null guard on `eventSuffix()` return — NPE risk
- 8 pre-existing `TrustScoreComputerTest` failures (expects `1.0`/`0.0`; Bayesian Beta gives `0.5`/`~0.333`)

**casehub-qhorus** — prompts sent: partial audit of `LedgerWriteService` (file truncated at line 140) + review audit

**Recommended next action in this repo**: targeted code scan of recent casehub-ledger commits for dismissed review findings — better than trying to reconstruct old session review output.

## Immediate Next Steps

1. **Group B starts with #60** (add `capabilityTag` to `LedgerAttestation`) — then #61 (capability-scoped trust scores, requires #68 + #60) and #62 (multi-dimensional, requires #68)
2. **Remaining Group A**: #56 (health checks), #57 (multi-attestation aggregation), #58 (compliance report — read consolidation check #6 first), #59 (ProvenanceSupplement enricher — requires #67 ✅)
3. **Code scan**: scan recent casehub-ledger commits for any Important findings that were dismissed — use `git log` + read the changed files directly (old session context is gone; direct code review is more reliable)

## References

| What | Path |
|---|---|
| Plan (Group A) | `docs/superpowers/plans/2026-04-28-group-a-55-54-53.md` |
| Plan (Prerequisites) | `docs/superpowers/plans/2026-04-28-prerequisite-refactors-67-68.md` |
| Latest ADRs | `adr/0005` – `adr/0007` |
| Latest blog | `blog/2026-04-29-mdp01-what-the-reviews-missed.md` |
| Cross-repo bugs | `https://github.com/casehubio/ledger/issues/72` |
| casehub-parent | `~/casehub-parent/` |
