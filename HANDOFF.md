# Quarkus Ledger — Session Handover
**Date:** 2026-04-23

## Current State

Feature-complete. 192 tests passing. All 38 GitHub issues closed. Repo is clean at `7929291`.

## What Landed This Session

**Trust score routing signals (Closes #32, #33, #34, #35)**
- `TrustScoreRoutingPublisher` fires CDI events after each `TrustScoreJob` run
- Three payload types as strategy selectors: `TrustScoreFullPayload`, `TrustScoreDeltaPayload`, `TrustScoreComputedAt`
- Sync/async per-consumer via `@Observes` / `@ObservesAsync` (CDI 4.x: `fire()` and `fireAsync()` are separate delivery paths)
- `BeanManager.resolveObserverMethods()` at `@PostConstruct` caches observer presence; skips DB pre-read when no delta observers registered
- `examples/trust-score-routing/` E2E example module
- `quarkus.ledger.trust-score.routing-delta-threshold` config key (default 0.01)
- EigenTrust config keys renamed: `eigentrust-enabled` → `eigentrust.enabled`, `eigentrust-alpha` → `eigentrust.alpha` (sub-interface `TrustScoreConfig.EigenTrustConfig`)

**Project health fixes (Closes #36, #37, #38)**
- 4 broken example modules fixed: `extends JpaLedgerEntryRepository` replaces Panache-based SPI reimplementation
- `correlationId` → `traceId` drift fixed across README, integration-guide, CAPABILITIES, AUDITABILITY, prov-dm-mapping
- Integration guide Step 4 rewritten: extend pattern, correct EntityManager usage, `listAll()` included
- `JpaLedgerEntryRepository.save()`: extracted `updateMerkleFrontier()` private method
- CAPABILITIES.md: EigenTrust "Research phase" corrected to "Implemented and opt-in"

**Issue tracking wired (Closes #35)**
- CLAUDE.md Work Tracking expanded with Phase 2 pre-code check and cross-cutting detection
- `docs/retro-issues.md` committed — permanent commit→issue mapping for all 181 commits
- All 38 issues closed; `performance`, `security`, `refactor` GitHub labels created

**Incident: corrupted commit removed**
- A subagent added `@PersistenceUnit("qhorus")` to 7 runtime classes (commit `8e595ce`)
- Removed via `git reset --hard a2636e1` + force push; repo clean

## Immediate Next Steps

No open issues. Candidates:
1. **Quarkiverse submission** — API stabilisation decision needed; project is structurally ready
2. **CaseHub consumer** — external dependency

## References

| What | Path |
|---|---|
| Design doc | `docs/DESIGN.md` |
| Research | `docs/RESEARCH.md` |
| Retro issues map | `docs/retro-issues.md` |
| Latest blog | `blog/2026-04-23-mdp01-routing-signals-health-cleanup.md` |
| GitHub repo | mdproctor/quarkus-ledger (38 issues, all closed) |
