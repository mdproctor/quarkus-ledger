# #61 Capability-Scoped Trust Scores — Spec

**Date:** 2026-05-02
**Status:** Approved
**Issue:** #61 (Group B epic #50)
**Depends on:** #60 (capabilityTag on LedgerAttestation — done)
**Enables:** #62 (multi-dimensional trust), `TrustGateService` Phase 2

## Goal

Extend `TrustScoreJob` to compute per-capability Beta trust scores alongside the existing
global score. Wire up `TrustGateService.meetsThreshold(actorId, capabilityTag, minTrust)` to
use the capability score with fallback to global.

## Schema

No changes. `actor_trust_score` already has `score_type` (GLOBAL/CAPABILITY/DIMENSION) and
`scope_key` (null for GLOBAL, capabilityTag string for CAPABILITY) with
`UNIQUE NULLS NOT DISTINCT (actor_id, score_type, scope_key)`. The SPI and JPA upsert
already accept `scopeKey`. No migration required.

## Literature basis for global score strategy

Three literature-backed positions on what the global score should aggregate:
- **Wang & Vassileva (2003):** global is the root node computed from all interactions; capabilities are derived conditionally. Supports Option B.
- **Fan et al. (2015):** global = frequency-weighted combination of dimension-specific scores. Supports Option C.
- **Semantic separation argument:** global = only explicitly cross-capability attestations (`"*"`). Supports Option A.

The literature does not converge. A pluggable `GlobalScoreStrategy` SPI resolves the ambiguity.
Default = Option B (all attestations), consistent with Wang & Vassileva.

## `GlobalScoreStrategy` SPI

**New file:** `runtime/src/main/java/io/casehub/ledger/runtime/service/GlobalScoreStrategy.java`

Follows the `DecayFunction` CDI pattern — pure Java, injectable.

```java
public interface GlobalScoreStrategy {

    /**
     * Select which attestations feed the global Beta model.
     * Option A: filter to CapabilityTag.GLOBAL ("*").
     * Option B (default): return all attestations.
     * Option C: return empty — global is derived via {@link #derive} instead.
     */
    List<LedgerAttestation> selectAttestations(List<LedgerAttestation> all);

    /**
     * Optionally derive the global score from already-computed capability scores,
     * overriding the Beta model. Return empty to use the Beta model score.
     * Option C returns a frequency-weighted combination here.
     */
    default Optional<TrustScoreComputer.ActorScore> derive(
            Map<String, TrustScoreComputer.ActorScore> capabilityScores,
            List<LedgerAttestation> allAttestations) {
        return Optional.empty();
    }
}
```

### Three implementations

| Class | CDI | Mechanism | Backed by |
|---|---|---|---|
| `AllAttestationsGlobalStrategy` | `@DefaultBean @ApplicationScoped` | All attestations → Beta model; `derive()` empty | Wang & Vassileva |
| `ExplicitGlobalAttestationsStrategy` | `@ApplicationScoped @Alternative` | Filter to `capabilityTag = "*"` only | Semantic separation |
| `FrequencyWeightedGlobalStrategy` | `@ApplicationScoped @Alternative` | `selectAttestations()` returns empty; `derive()` = Σ(count_i/total × score_i) | Fan et al. (2015) |

Consumers activate non-default implementations via
`quarkus.arc.selected-alternatives=io.casehub.ledger.runtime.service.ExplicitGlobalAttestationsStrategy`.

## `TrustScoreJob` changes

Inject `GlobalScoreStrategy`. Reorder per-actor computation so capability scores are computed
before global (required for Option C's `derive()`):

```
for each actor:
  allAttestations = flatten all attestations for this actor's decisions

  // Capability pass (new)
  distinctTags = { capabilityTag | att ∈ allAttestations, capabilityTag ≠ "*" }
  capabilityScores = {}
  for each tag in distinctTags:
      filteredMap = attestationsByEntry filtered to attestations where capabilityTag = tag
      capScore = computer.compute(decisions, filteredMap, now)
      trustRepo.upsert(actorId, CAPABILITY, tag, actorType, capScore.*, now)
      capabilityScores[tag] = capScore

  // Global pass (modified)
  selected = globalScoreStrategy.selectAttestations(allAttestations)
  selectedMap = rebuild attestationsByEntry from selected attestations
  globalScore = computer.compute(decisions, selectedMap, now)
  derived = globalScoreStrategy.derive(capabilityScores, allAttestations)
  finalScore = derived.orElse(globalScore)
  trustRepo.upsert(actorId, GLOBAL, null, actorType, finalScore.*, now)
```

No changes to `TrustScoreComputer`. Filtering the attestation map is done inline before
calling `compute()`. EigenTrust pass is unchanged.

The previous-snapshot logic in `TrustScoreJob` already filters to `scoreType == GLOBAL` —
CAPABILITY rows are excluded from delta computation. This remains correct.

## `TrustGateService` Phase 2

Replace the TODO stub in `meetsThreshold(actorId, capabilityTag, minTrust)`:

```java
public boolean meetsThreshold(final String actorId, final String capabilityTag,
        final double minTrust) {
    return trustRepo
            .findByActorIdAndTypeAndKey(actorId, ScoreType.CAPABILITY, capabilityTag)
            .map(s -> s.trustScore >= minTrust)
            .orElseGet(() -> meetsThreshold(actorId, minTrust));
}
```

Add capability-scoped `currentScore` overload:

```java
public Optional<Double> currentScore(final String actorId, final String capabilityTag) {
    return trustRepo
            .findByActorIdAndTypeAndKey(actorId, ScoreType.CAPABILITY, capabilityTag)
            .map(s -> s.trustScore);
}
```

## `TrustScoreRoutingPublisher` / payloads

No changes. `TrustScoreFullPayload` already holds `List<ActorTrustScore>` from `findAll()`,
which will now naturally include CAPABILITY rows. Consumers filter by `scoreType` if needed.

## Tests

### Unit tests — `GlobalScoreStrategyTest` (pure Java)

| Test | Assertion |
|---|---|
| `allAttestations_returnsAll` | `AllAttestationsGlobalStrategy.selectAttestations` returns full list |
| `explicitGlobal_filtersToStarOnly` | Keeps only `capabilityTag = "*"` |
| `explicitGlobal_emptyIfNoGlobalAttestations` | Only capability-tagged input → empty output |
| `frequencyWeighted_selectAttestationsReturnsEmpty` | `FrequencyWeightedGlobalStrategy.selectAttestations` always empty |
| `frequencyWeighted_deriveWeightsByCount` | 3 security, 1 style → `(3/4)×secScore + (1/4)×styleScore` |
| `frequencyWeighted_noCapabilities_returnsEmpty` | Empty capability map → `Optional.empty()` |

### Integration tests — `TrustScoreCapabilityIT` (`@QuarkusTest`, `TrustScoreTestProfile`)

**Happy path:**
- Actor with `"security-review"` + `"style-review"` attestations → CAPABILITY rows for each; GLOBAL uses all (Option B)
- GLOBAL score lies between the two capability scores

**Correctness:**
- Capability Beta model is isolated: only that tag's attestations contribute
- Actor with only `"*"` attestations → no CAPABILITY rows; GLOBAL row exists
- `TrustGateService.meetsThreshold(id, "security-review", 0.8)` uses CAPABILITY score
- No capability score for tag → fallback to GLOBAL

**Robustness:**
- Actor with no attestations → GLOBAL = 0.5; no CAPABILITY rows
- Pre-B1 actors (all `"*"`) → GLOBAL unchanged; no CAPABILITY rows written

## Out of scope

- Routing publisher changes (consumers get CAPABILITY rows automatically via `findAll()`)
- `FrequencyWeightedGlobalStrategy` as default (deferred until real deployment data informs weights)
- #62 multi-dimensional trust (separate issue)
