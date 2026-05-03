# ADR 0008 — GlobalScoreStrategy SPI

**Date:** 2026-05-03
**Status:** Accepted
**Refs:** #61

## Context

`TrustScoreJob` needed to compute per-capability Beta scores using `capabilityTag` (added in #60).
A key design question was what the GLOBAL `ActorTrustScore` should aggregate:

- **Option A:** Only `capabilityTag = "*"` attestations
- **Option B:** All attestations regardless of tag (holistic view)
- **Option C:** Frequency-weighted combination of capability scores

Research across three papers found genuine disagreement:
- Wang & Vassileva (2003) — global is the root node computed from all interactions (Option B)
- Fan et al. (2015) — global = weighted combination of dimension-specific scores (Option C)
- Semantic separation argument — global = explicit cross-capability statements (Option A)

Key finding from Wang & Vassileva: "We do not treat the differentiated trusts as compositional.
Usually the relationship between different aspects of an agent is not just compositional, but
complex and correlative." The global trust score is the root node; capability scores are derived
conditional views — not the other way around.

## Decision

Introduce `GlobalScoreStrategy` as a CDI SPI with two methods:
1. `selectAttestations(all)` — filter attestations before the global Beta model
2. `derive(capabilityScores, all)` — optionally override the Beta result after capability scores are computed

Three built-in implementations:
- `AllAttestationsGlobalStrategy` — `@DefaultBean`; Option B (Wang & Vassileva)
- `ExplicitGlobalAttestationsStrategy` — `@Alternative`; Option A
- `FrequencyWeightedGlobalStrategy` — `@Alternative`; Option C (Fan et al.)

Default = Option B. Rationale: simplest, no dependency on capability scores being computed
first, consistent with the root-node model in Wang & Vassileva.

## Consequences

- `TrustScoreJob` injects `GlobalScoreStrategy`; capability scores are computed before global
  (required for Option C's `derive()` call)
- Consumers switch strategies via `quarkus.arc.selected-alternatives` — no code change
- Option C (`FrequencyWeightedGlobalStrategy`) is available as an `@Alternative` but not the
  default; production deployments with real data can evaluate whether frequency weighting
  better reflects holistic trust in their context
- `TrustGateService.meetsThreshold(actorId, capabilityTag, minTrust)` now queries CAPABILITY
  score first and falls back to GLOBAL when none exists (Phase 2)
- Follow-up: revisit default after real deployment data is available

## References

- Wang & Vassileva (2003): [Semantic Scholar](https://www.semanticscholar.org/paper/Bayesian-Network-Based-Trust-Model-in-Peer-to-Peer-Wang-Vassileva/bed571c548e1d858f4acd7e351289b28dd56de7e)
- Fan et al. (2015): DOI [10.1007/s11633-014-0840-3](https://doi.org/10.1007/s11633-014-0840-3)
- Jøsang & Ismail (2002): [BLED 2002](https://aisel.aisnet.org/bled2002/41/)
