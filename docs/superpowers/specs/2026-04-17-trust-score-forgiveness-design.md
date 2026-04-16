# Design Spec — Trust Score Forgiveness Mechanism

**Date:** 2026-04-17
**Issue:** #8 — Forgiveness mechanism in EigenTrust trust scoring
**Epic:** #6 — Agentic AI compliance and trust quality
**ADR:** `adr/0001-forgiveness-mechanism-omits-severity-dimension.md`
**Status:** Approved

---

## Problem

The current `TrustScoreComputer` uses EigenTrust with exponential decay. Any decision
that receives a negative attestation (FLAGGED or CHALLENGED) contributes a penalty
of 0.0 or 0.5 to the weighted average — permanently, regardless of:

- How old the failure was
- Whether it was a one-off or a pattern
- How much clean behaviour followed it

AI agents fail transiently — network timeouts, model rate limits, temporary degradation.
Pure EigenTrust treats these identically to deliberate bad behaviour. An agent that
fails once and recovers fully is permanently disadvantaged against a fresh agent with
no history.

---

## Design Constraint

> **Disabled by default. Zero behaviour change when disabled.**

When `quarkus.ledger.trust-score.forgiveness.enabled=false` (the default), the
`TrustScoreComputer` must produce byte-for-byte identical results to the current
implementation. This is verified by a regression test that runs both paths on the
same input and asserts equality.

---

## Algorithm

### Academic basis

Two-parameter forgiveness (recency + frequency) matches the empirically evaluated
models in the literature (Binmad & Li 2016, e-business agent forgiveness models).
Severity was considered and deliberately excluded — see ADR 0001.

### Formula

For each decision where `decisionScore < 1.0` (i.e., there were any negative
attestations):

```
negativeDecisions = count of decisions in this actor's history
                    that had at least one negative attestation

recencyForgiveness = 2^(-ageInDays / forgiveness.halfLifeDays)
frequencyLeniency  = negativeDecisions ≤ forgiveness.frequencyThreshold ? 1.0 : 0.5

F = recencyForgiveness × frequencyLeniency

adjustedDecisionScore = decisionScore + F × (1.0 - decisionScore)
```

**What each factor does:**

- `recencyForgiveness` — old failures fade. At `halfLifeDays=30`, a failure 30 days
  ago contributes 50% of its original penalty. At 60 days, 25%. At 90 days, 12.5%.
  This is the single most impactful lever.

- `frequencyLeniency` — distinguishes one-off failures from patterns. Below the
  threshold, full leniency (F multiplied by 1.0). Above it, half leniency (F × 0.5).
  Default threshold of 3 means: up to 3 negative decisions → treated as potentially
  transient; 4 or more → pattern detected, forgiveness reduced.

**Clean decisions (score = 1.0) are not affected.** The forgiveness branch is only
entered when `decisionScore < 1.0`.

### Example trace

Actor with `halfLifeDays=30`, `frequencyThreshold=3`, 2 total negative decisions:

```
Failure 60 days ago, decisionScore=0.0:
  recencyForgiveness = 2^(-60/30) = 0.25
  frequencyLeniency  = 2 ≤ 3 → 1.0
  F = 0.25
  adjustedScore = 0.0 + 0.25 × 1.0 = 0.25   (was 0.0 without forgiveness)

Failure 5 days ago, decisionScore=0.5:
  recencyForgiveness = 2^(-5/30) ≈ 0.89
  frequencyLeniency  = 2 ≤ 3 → 1.0
  F ≈ 0.89
  adjustedScore = 0.5 + 0.89 × 0.5 ≈ 0.945  (was 0.5 without forgiveness)
```

---

## Configuration

Three new keys added as a nested `ForgivenessConfig` interface inside
`LedgerConfig.TrustScoreConfig`:

```java
/** Forgiveness mechanism settings for trust score computation. */
interface ForgivenessConfig {

    /**
     * When {@code true}, the forgiveness mechanism modulates the penalty of
     * negative decisions based on their age and the actor's negative decision
     * frequency. Off by default — enabling has no effect until trust scoring
     * is also enabled.
     *
     * @return {@code true} if forgiveness is active; {@code false} by default
     */
    @WithDefault("false")
    boolean enabled();

    /**
     * Number of negative decisions at or below which the actor is treated as
     * a potential one-off offender and receives full leniency. Above this
     * threshold, leniency is halved.
     *
     * @return frequency threshold (default 3)
     */
    @WithDefault("3")
    int frequencyThreshold();

    /**
     * Half-life in days for the forgiveness recency decay. A failure this many
     * days in the past contributes 50% of its original penalty; at double the
     * half-life, 25%; and so on. Shorter values forgive faster.
     *
     * @return forgiveness half-life in days (default 30)
     */
    @WithDefault("30")
    int halfLifeDays();
}
```

`TrustScoreConfig` gains: `ForgivenessConfig forgiveness();`

---

## Code Changes

### `TrustScoreComputer.java`

New inner record (replaces no existing code):

```java
/**
 * Parameters for the optional forgiveness mechanism.
 * Use {@link #disabled()} for the default no-forgiveness path.
 */
public record ForgivenessParams(boolean enabled, int frequencyThreshold, int halfLifeDays) {
    /** Returns a params instance that disables forgiveness entirely. */
    public static ForgivenessParams disabled() {
        return new ForgivenessParams(false, 0, 0);
    }
}
```

Existing constructor delegates to the new one — zero source compatibility break:

```java
/** Existing constructor — unchanged, delegates to two-param version. */
public TrustScoreComputer(final int halfLifeDays) {
    this(halfLifeDays, ForgivenessParams.disabled());
}

/** New constructor — used by TrustScoreJob when forgiveness is configured. */
public TrustScoreComputer(final int halfLifeDays, final ForgivenessParams forgiveness) {
    this.halfLifeDays = halfLifeDays > 0 ? halfLifeDays : 90;
    this.forgiveness = forgiveness;
}
```

Inside `compute()`, `negativeDecisions` is counted once before the loop:

```java
final long negativeDecisions = decisions.stream()
    .filter(e -> {
        final List<LedgerAttestation> a = attestationsByEntryId.getOrDefault(e.id, List.of());
        return a.stream().anyMatch(att ->
            att.verdict == AttestationVerdict.FLAGGED ||
            att.verdict == AttestationVerdict.CHALLENGED);
    })
    .count();
```

Then inside the per-decision loop, after `decisionScore` is determined:

```java
final double effectiveScore;
if (forgiveness.enabled() && decisionScore < 1.0) {
    final double recencyF = Math.pow(2.0, -(double) ageInDays / forgiveness.halfLifeDays());
    final double freqF    = negativeDecisions <= forgiveness.frequencyThreshold() ? 1.0 : 0.5;
    effectiveScore = decisionScore + (recencyF * freqF) * (1.0 - decisionScore);
} else {
    effectiveScore = decisionScore;
}
// use effectiveScore instead of decisionScore in the weighted sum
```

### `TrustScoreJob.java`

`runComputation()` constructs the computer with forgiveness params:

```java
final TrustScoreComputer computer = new TrustScoreComputer(
    config.trustScore().decayHalfLifeDays(),
    new TrustScoreComputer.ForgivenessParams(
        config.trustScore().forgiveness().enabled(),
        config.trustScore().forgiveness().frequencyThreshold(),
        config.trustScore().forgiveness().halfLifeDays()));
```

### `LedgerConfig.java`

`TrustScoreConfig` gains:

```java
/**
 * Forgiveness mechanism — modulates penalties for negative decisions based
 * on their age and the actor's overall negative decision frequency.
 *
 * @return forgiveness sub-configuration
 */
ForgivenessConfig forgiveness();
```

---

## Testing

### Unit tests (6 new, all in `TrustScoreComputerTest`)

| Test | What it proves |
|---|---|
| `forgiveness_disabled_identicalToBaseline` | `ForgivenessParams.disabled()` produces identical result to `new TrustScoreComputer(90)` on identical input |
| `forgiveness_singleTransientFailure_recovers` | 1 flagged + 5 clean decisions → higher score than pure EigenTrust |
| `forgiveness_repeatOffender_lessForgiven` | 5 negative decisions (> threshold=3) → lower F than 2 negatives on identical recency |
| `forgiveness_oldFailure_forgiven` | Failure 60 days ago, halfLife=30 → F≈0.25 → `adjustedScore` visibly higher than `decisionScore` |
| `forgiveness_recentFailure_minimalForgiveness` | Failure today → recencyF≈1.0 → score raised toward 1.0 |
| `forgiveness_cleanDecisions_unaffected` | All clean decisions → identical score with forgiveness enabled or disabled |

All 16 existing tests must continue to pass unchanged.

---

## Zero-Complexity Verification

| Scenario | Forgiveness tables touched? | Behaviour changed? |
|---|---|---|
| `forgiveness.enabled=false` (default) | — | ❌ No change |
| `forgiveness.enabled=true`, no negative decisions | — | ❌ No change (branch not entered) |
| `forgiveness.enabled=true`, has negative decisions | — | ✅ Score adjusted upward |

No new database tables. No new Flyway migration. No schema change.

---

## References

- Issue #8
- ADR `adr/0001-forgiveness-mechanism-omits-severity-dimension.md`
- [Computational Models Based on Forgiveness Mechanism for Untrustworthy Agents (Springer 2016)](https://link.springer.com/chapter/10.1007/978-3-319-27000-5_3)
- [Reputation Model with Forgiveness Factor for E-Business Agents (Springer 2010)](https://link.springer.com/chapter/10.1007/978-3-642-14306-9_41)
