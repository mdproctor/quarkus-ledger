# CaseHub Ledger — Session Handover
**Date:** 2026-05-04

## Current State

`casehub-ledger` v0.2-SNAPSHOT. Clean working tree. 272 tests, BUILD SUCCESS, CI green.
Group B: #60 ✅ #61 ✅. #62 (multi-dimensional trust) is next.

## What Landed This Session

**#61 capability-scoped trust scores (Group B, epic #50):**
- `GlobalScoreStrategy` SPI — 3 implementations: `AllAttestationsGlobalStrategy` (@DefaultBean,
  Option B), `ExplicitGlobalAttestationsStrategy` (@Alternative), `FrequencyWeightedGlobalStrategy`
  (@Alternative). Activation via `quarkus.arc.selected-alternatives`
- `TrustScoreJob` capability pass: O(M) single-pass nested `groupingBy`; CAPABILITY rows written
  per distinct tag; strategy injected; capability pass before global
- `TrustGateService` Phase 2: `meetsThreshold(actorId, capabilityTag, minTrust)` — CAPABILITY
  score first, falls back to GLOBAL; `currentScore(actorId, capabilityTag)` added
- ADR 0008: GlobalScoreStrategy SPI with Wang & Vassileva, Fan et al., Jøsang & Ismail citations
- Known limitation documented: `FrequencyWeightedGlobalStrategy.derive()` alpha/beta fields are
  approximations, not valid Beta posteriors — `trustScore` is correct, alpha/beta are not

## Immediate Next Steps

1. **#62 (B3)** — multi-dimensional trust infrastructure: `DIMENSION` rows in `ActorTrustScore`,
   `scope_key = dimensionName`, analogous to #61 but for trust dimensions (e.g. "thoroughness")
2. **Group A remaining:** #56 (health checks), #57 (multi-attestation aggregation),
   #58 (compliance report), #59 (ProvenanceSupplement enricher)

## References

| What | Path |
|---|---|
| Latest ADRs | `adr/0006` – `adr/0008` |
| Latest blog | `blog/2026-05-04-mdp01-when-papers-disagree.md` |
| Cross-repo bugs | `https://github.com/casehubio/ledger/issues/72` |
| Previous handover | `git show HEAD~1:HANDOFF.md` |
