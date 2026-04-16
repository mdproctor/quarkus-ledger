# 0001 — Forgiveness mechanism omits severity as a distinct dimension

Date: 2026-04-17
Status: Accepted

## Context and Problem Statement

The forgiveness mechanism for `TrustScoreComputer` was designed with three
candidate dimensions: recency (old failures fade), frequency (repeat offenders
forgiven less), and severity (FLAGGED vs CHALLENGED treated differently). The
question is whether severity warrants a dedicated config parameter and formula
term alongside recency and frequency.

## Decision Drivers

* The forgiveness mechanism must be off by default with zero behaviour change
  when disabled — the simpler the formula, the easier that guarantee is to verify
* Real interaction data from Tarkus/Qhorus does not yet exist; any severity weight
  would be an uncalibrated guess
* YAGNI: parameters added without empirical basis become permanent dead weight

## Considered Options

* **Option A — Two parameters:** recency half-life + frequency threshold only
* **Option B — Three parameters:** recency + frequency + severity weight per verdict type
* **Option C — Severity only via verdict score:** no explicit parameter; implicit in existing decision score logic

## Decision Outcome

Chosen option: **Option A**, because severity discrimination is already encoded
implicitly in the existing `decisionScore` calculation (a FLAGGED verdict with
peers pushing back scores 0.5; unopposed scores 0.0 — the attestation majority
acts as a severity signal). Adding an explicit severity weight on top of this
double-counts the same signal. Academic literature (Binmad & Li 2016; e-business
agent forgiveness models) confirms that empirically validated forgiveness mechanisms
use recency + frequency; severity appears in theoretical frameworks but not in
models that were experimentally evaluated.

### Positive Consequences

* Forgiveness formula stays two-parameter — easy to explain, test, and tune
* No risk of miscalibrated severity weights in the absence of real data
* Existing `decisionScore` semantics remain the sole arbiter of verdict severity

### Negative Consequences / Tradeoffs

* If real data later shows FLAGGED and CHALLENGED produce meaningfully different
  recovery trajectories, this decision must be revisited (supersede this ADR)
* A consumer who wants coarser or finer severity discrimination cannot do so via config

## Pros and Cons of the Options

### Option A — Two parameters (recency + frequency)

* ✅ Academically grounded — matches empirically evaluated models in literature
* ✅ Implicit severity already handled by `decisionScore` majority logic
* ✅ Fewer config parameters, easier cold-start calibration
* ❌ Cannot distinguish FLAGGED from CHALLENGED in the forgiveness path explicitly

### Option B — Three parameters (recency + frequency + severity)

* ✅ Explicit, independently tunable per verdict type
* ❌ Double-counts severity already present in `decisionScore`
* ❌ Requires calibration data that does not yet exist
* ❌ Adds a config parameter with no empirical baseline

### Option C — Severity only via verdict score (no forgiveness dimension)

* ✅ Zero new parameters
* ❌ Ignores recency and frequency entirely — the two most impactful levers
* ❌ Not aligned with the forgiveness design goal

## Links

* Issue #8 — Forgiveness mechanism in EigenTrust
* [Computational Models Based on Forgiveness Mechanism for Untrustworthy Agents](https://link.springer.com/chapter/10.1007/978-3-319-27000-5_3) (Binmad & Li, 2016)
* [Reputation Model with Forgiveness Factor for E-Business Agents](https://link.springer.com/chapter/10.1007/978-3-642-14306-9_41) (Springer, 2010)
* `docs/RESEARCH.md` — priority matrix and research sources
