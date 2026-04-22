# Trust Score Routing Signals — Design Spec

**Date:** 2026-04-22
**Feature:** `quarkus.ledger.trust-score.routing-enabled`
**Status:** Approved

---

## Problem

`quarkus.ledger.trust-score.routing-enabled` is wired in `LedgerConfig` but not implemented.
Routing layers (e.g. CaseHub task assignment) have no mechanism to receive trust score signals
after each computation run.

---

## Design

### Selection mechanism — CDI payload types

Consumers select their strategy by observing a payload type. Three payload types cover the
three information granularities:

| Payload type | What it carries | When to use |
|---|---|---|
| `TrustScoreFullPayload` | `List<ActorTrustScore>` — all current scores | Consumer wants to rebuild a full ranked list |
| `TrustScoreDeltaPayload` | `List<TrustScoreDelta>` — changed actors only | Consumer maintains a cache, wants incremental updates |
| `TrustScoreComputedAt` | `Instant computedAt`, `int actorCount` | Consumer just needs a "scores refreshed" signal |

### Sync vs async — per observer, CDI-native

Consumers annotate with `@Observes` (synchronous, inline) or `@ObservesAsync` (async,
managed executor) on their observer method. The publisher always calls `event.fire()` —
CDI routes sync and async observers accordingly. No custom annotations needed.

```java
// Synchronous — runs inline in the scheduler thread
void onScores(@Observes TrustScoreFullPayload payload) { ... }

// Asynchronous — queued on CDI managed executor
CompletionStage<Void> onScores(@ObservesAsync TrustScoreFullPayload payload) { ... }
```

---

## Components

### New classes — `runtime/src/main/java/io/quarkiverse/ledger/runtime/service/routing/`

| Class | Kind | Purpose |
|---|---|---|
| `TrustScoreFullPayload` | record | Immutable snapshot of all current scores after computation |
| `TrustScoreDeltaPayload` | record | Changed actors only (above delta threshold) |
| `TrustScoreComputedAt` | record | Lightweight notification — timestamp + actor count |
| `TrustScoreDelta` | record | Single actor delta: actorId, previousScore, newScore, previousGlobalScore, newGlobalScore |
| `TrustScoreRoutingPublisher` | `@ApplicationScoped` CDI bean | Holds `Event<T>` for each payload type; dispatches after computation |

### Modified — `LedgerConfig.TrustScoreConfig`

Add `routingDeltaThreshold()` method with `@WithDefault("0.01")`.

### Modified — `TrustScoreJob`

`runComputation()` calls `publisher.publish(allScores, previousSnapshot, now)` after writing
scores. One line added at the end of the method.

### New config key

```
quarkus.ledger.trust-score.routing-delta-threshold=0.01
```

Minimum absolute score change (`|newScore - oldScore|`) for an actor to appear in a
`TrustScoreDeltaPayload`. Default `0.01`. Prevents noise from floating-point drift.

---

## Data Flow

```
TrustScoreJob.runComputation()
  │
  ├─ [DeltaPayload observers exist] read snapshot: ActorTrustScoreRepository.findAll()
  │
  ├─ compute Bayesian Beta scores → write to DB  (existing)
  │
  ├─ [eigentrust enabled] compute EigenTrust → write global scores  (existing)
  │
  └─ TrustScoreRoutingPublisher.publish(allScores, previousSnapshot, now)
       │
       ├─ [routing-enabled=false] return immediately
       │
       ├─ [NotifyObserver exists] event.fire(TrustScoreComputedAt(now, count))
       │
       ├─ [FullObserver exists] event.fire(TrustScoreFullPayload(allScores))
       │
       └─ [DeltaObserver exists] compute deltas (|new − old| > threshold)
                                  event.fire(TrustScoreDeltaPayload(deltas))
```

**Delta pre-read:** executed once per job run, only when at least one `TrustScoreDeltaPayload`
observer is registered. Observer presence is detected at startup via `BeanManager` and cached —
no per-run reflection.

**Previous scores on first run:** no prior `ActorTrustScore` rows → `previousScore = 0.0`
for all actors. All actors appear in the delta payload.

---

## Error Handling

**Synchronous observer throws:** publisher catches `Exception` per payload type, logs a
warning, continues dispatching remaining payload types. A broken consumer never crashes the
scheduler job or blocks other observers.

**Asynchronous observer throws:** CDI manages the `CompletionStage`; Quarkus logs failures
via its async observer exception handler. Publisher is fire-and-forget.

**routing-enabled=false with registered observers:** publisher returns immediately. Observers
are never called. Intentional — routing is an opt-in feature.

---

## Configuration Reference

| Key | Default | Description |
|---|---|---|
| `quarkus.ledger.trust-score.routing-enabled` | `false` | Gates all routing signal dispatch |
| `quarkus.ledger.trust-score.routing-delta-threshold` | `0.01` | Minimum score change for delta inclusion |

---

## Test Plan

### Unit tests — `TrustScoreRoutingPublisherTest` (pure Java, no CDI)

| Category | Test |
|---|---|
| Happy path | routing disabled → no events fired |
| Happy path | no observers → no events fired, no delta pre-read triggered |
| Happy path | full observer registered → receives all scores |
| Happy path | delta observer registered → pre-read happens, delta computed |
| Happy path | notify observer registered → correct actorCount and timestamp |
| Correctness | delta threshold: change < 0.01 → actor excluded |
| Correctness | delta threshold: change = 0.01 → actor included |
| Correctness | first run (no previous scores) → all actors in delta with previousScore=0.0 |
| Robustness | observer throws → exception swallowed, other observers still called |
| Robustness | all three observers registered → all three receive payloads independently |

### Integration tests — `TrustScoreRoutingIT` (`@QuarkusTest`)

| Category | Test |
|---|---|
| Happy path | `TrustScoreJob.runComputation()` → full observer receives correct scores |
| Happy path | delta observer receives only changed actors on second run |
| Happy path | notify observer receives actorCount matching DB state |
| Happy path | async observer (`@ObservesAsync`) receives payload (verified via `CountDownLatch`) |
| Robustness | routing-enabled=false → observers registered but never called |

### E2E example — `examples/trust-score-routing/`

Simulated task assignment scenario demonstrating all three payload types:
- Sync full observer building a ranked actor list
- Async notify observer logging a refresh signal
- Integration test exercising the full pipeline: ledger entries → attestations →
  trust score job → routing events

---

## New Classes Summary

```
runtime/src/main/java/io/quarkiverse/ledger/runtime/service/routing/
├── TrustScoreFullPayload.java
├── TrustScoreDeltaPayload.java
├── TrustScoreComputedAt.java
├── TrustScoreDelta.java
└── TrustScoreRoutingPublisher.java

examples/trust-score-routing/
└── (new example module — structure mirrors existing examples)
```
