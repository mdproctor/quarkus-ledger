# ADR 0007 — DecayFunction SPI

**Date:** 2026-04-28
**Status:** Accepted
**Refs:** #55

## Context

`TrustScoreComputer` had a hardcoded exponential decay formula. Issue #55 required adding
a valence multiplier so FLAGGED attestations decay slower. Rather than adding a second
magic constant to the formula, the decay function was extracted as an SPI — an approach
the #55 consolidation check explicitly required.

## Decision

Introduce `DecayFunction` as a `@FunctionalInterface` SPI:
`double weight(long ageInDays, AttestationVerdict verdict)`

`ExponentialDecayFunction` is the `@DefaultBean` CDI implementation. It applies:
- Base decay: `2^(-ageInDays / halfLifeDays)` (unchanged from prior formula)
- Valence multiplier: FLAGGED/CHALLENGED multiply by `flaggedPersistenceMultiplier` (default 0.5)

`TrustScoreComputer` now accepts `DecayFunction` as a constructor parameter. A second
`int halfLifeDays` constructor is preserved for unit tests (simple exponential, no asymmetry).

## Consequences

- Future decay strategies (linear, step, per-actor) register as `@Alternative` CDI beans
- `TrustScoreComputerTest` keeps `new TrustScoreComputer(90)` syntax unchanged
- `TrustScoreJob` injects `DecayFunction` rather than reading `halfLifeDays` directly
- New config key: `quarkus.ledger.decay.flagged-persistence-multiplier` (default 0.5)
