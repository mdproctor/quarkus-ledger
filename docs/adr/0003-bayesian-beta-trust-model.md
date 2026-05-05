# 0003 — Bayesian Beta model replaces ForgivenessParams

Date: 2026-04-20
Status: Accepted

## Context and Problem Statement

The `TrustScoreComputer` classified each decision as 1.0 / 0.5 / 0.0 and computed a
recency-weighted average. Two problems: (1) no uncertainty model — an actor with 1 positive
attestation scored identically to one with 100 positives; (2) `ForgivenessParams` was a
patch on top of a coarse model, adding complexity without fixing the underlying issue.

## Decision

Replace the classification + weighted-average approach with a Bayesian Beta distribution.

Each attestation contributes a recency-weighted increment to α (positive) or β (negative)
using the attestation's own timestamp: `weight = 2^(-ageInDays / halfLifeDays)`. Prior is
Beta(1,1). Score = α/(α+β). Unattested decisions contribute nothing — the prior handles
"no information" with score 0.5.

`ForgivenessParams` is removed entirely. The Beta model supersedes both of its concerns:
recency of negative attestations fades naturally via weighting on β; frequency effects
are implicit in the α/β ratio.

## Consequences

* **Better:** uncertainty is modelled — evidence quality is captured, not just direction.
* **Better:** old negative attestations fade without a separate forgiveness mechanism.
* **Better:** simpler API — one constructor parameter, no `ForgivenessParams`.
* **Changed:** unattested decisions no longer score 1.0 (clean) — they score 0.5 (unknown).
  Consumers relying on "clean by default" behaviour must add attestations to signal quality.
* **Supersedes:** ADR 0001 (forgiveness severity dimension — forgiveness mechanism removed).
