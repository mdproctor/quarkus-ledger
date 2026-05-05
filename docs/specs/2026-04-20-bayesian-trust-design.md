# Bayesian Beta Trust Scoring — Design Spec

**Date:** 2026-04-20
**Status:** Approved
**Supersedes:** `2026-04-17-trust-score-forgiveness-design.md`

---

## Problem

The current `TrustScoreComputer` classifies each decision as 1.0 / 0.5 / 0.0 based on its attestation counts, then weights those scores by the decision's age. Two problems:

1. **No uncertainty model.** An actor with 1 positive attestation scores the same as one with 100. The model doesn't know the difference.
2. **Forgiveness is a patch.** `ForgivenessParams` was added to soften the hard classification for AI agents that fail transiently. It works, but it's layered on top of a coarse model rather than fixing the underlying issue.

---

## Solution

Replace the classification + weighted-average approach with a **Bayesian Beta distribution** updated per attestation with recency-decayed contributions.

---

## Algorithm

**Inputs:**
- `decisions` — all EVENT ledger entries where this actor was the decision-maker
- `attestationsByEntryId` — map from entry ID to its attestations
- `now` — reference timestamp
- `halfLifeDays` — recency decay parameter (same as current)

**Accumulation:**

Start with prior `α = 1.0`, `β = 1.0` (Beta(1,1) — uniform, no history → score 0.5).

For each attestation across all decisions:
```
ageInDays = duration(attestation.occurredAt → now).toDays()
recencyWeight = 2^(-ageInDays / halfLifeDays)

SOUND or ENDORSED  → α += recencyWeight
FLAGGED or CHALLENGED → β += recencyWeight
```

Age is taken from the **attestation's own `occurredAt`**, not the decision's age.

**Score:** `trustScore = α / (α + β)`, clamped to [0.0, 1.0].

**Unattested decisions** contribute nothing. The prior handles "no attestation history" — an actor with many unreviewed decisions scores 0.5 (maximum uncertainty), not 1.0 (spuriously clean).

---

## Properties

| Scenario | Current score | Beta score |
|---|---|---|
| No history | 0.5 | 0.5 |
| 1 positive, 0 negative | 1.0 | 2/3 ≈ 0.667 |
| 100 positive, 0 negative | 1.0 | 101/102 ≈ 0.990 |
| 1 positive, 1 negative | 0.5 | 2/3 (tied α=β+prior) |
| 10 positive, 1 negative | ~0.9 | 11/13 ≈ 0.846 |
| Old negatives, recent positives | depends | recent positives dominate via recencyWeight |

Uncertainty shrinks as evidence accumulates. A new agent and a veteran agent with perfect records are no longer indistinguishable.

---

## API Changes

### `TrustScoreComputer`

**Removed:**
- `ForgivenessParams` record — superseded. Recency decay on β naturally fades old negatives. Frequency leniency is implicitly captured by the α/β ratio.
- Two-arg constructor `TrustScoreComputer(int, ForgivenessParams)` — removed.

**Kept:**
- Single-arg constructor `TrustScoreComputer(int halfLifeDays)` — unchanged signature.
- `compute(List<LedgerEntry>, Map<UUID, List<LedgerAttestation>>, Instant)` — unchanged signature, new implementation.

### `ActorScore` record

**Before:** `trustScore, decisionCount, overturnedCount, appealCount, attestationPositive, attestationNegative`

**After:** `trustScore, alpha, beta, decisionCount, overturnedCount, attestationPositive, attestationNegative`

- `alpha` — final accumulated α value (prior + positive contributions); informative
- `beta` — final accumulated β value (prior + negative contributions); informative
- `appealCount` — dropped (was always 0, never designed)

---

## Scope

- **`TrustScoreComputer.java`** — algorithm rewrite, `ForgivenessParams` removal, `ActorScore` reshape
- **`TrustScoreJob.java`** — switch to single-arg constructor, remove `ForgivenessParams` construction
- **`LedgerConfig.java`** — remove `ForgivenessConfig` sub-interface and `forgiveness()` accessor from `TrustScoreConfig`
- **`TrustScoreComputerTest.java`** — updated for new model
- **`TrustScoreForgivenessIT.java`** — replaced with `TrustScoreIT` covering Beta-specific scenarios
- **Schema** — no changes
- **`application.properties`** — remove any `quarkus.ledger.trust-score.forgiveness.*` defaults

---

## Tests

**Unit (`TrustScoreComputerTest`):**
- No history → `trustScore = 0.5`, `alpha = 1.0`, `beta = 1.0`
- All positive attestations → score approaches 1.0 as count grows
- All negative attestations → score approaches 0.0 as count grows
- Mixed → score proportional to α/(α+β)
- Recency: recent positive outweighs equal-count older negatives
- Uncertainty: 1 positive produces lower α and lower score than 100 positives

**Integration (`TrustScoreIT` — replaces `TrustScoreForgivenessIT`):**
- Full round-trip: persist entries + attestations, run `TrustScoreJob`, verify stored `ActorTrustScore.score`
- Verify old negatives fade: two runs with time-shifted `now` show score recovery
