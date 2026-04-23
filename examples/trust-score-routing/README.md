# Example: Trust Score Routing Signals

This example demonstrates how to consume CDI routing signals fired by `quarkus-ledger` after
each nightly trust score computation — without polling the database.

## Why routing signals?

Trust scores are computed nightly by `TrustScoreJob`. Any consumer that needs to react — a
router that ranks agents for task assignment, a dashboard that refreshes its display, a
circuit breaker that gates access — would otherwise have to poll the database on every request.
Routing signals solve this: the ledger fires a CDI event after each computation run, and
consumers update their in-memory state once per recomputation cycle.

## The three payload types

| Payload | When to use |
|---|---|
| `TrustScoreFullPayload` | You need the complete ranked list — e.g. a router that sorts all agents by score on every recompute |
| `TrustScoreDeltaPayload` | You only care about actors whose scores changed beyond a configurable threshold — e.g. an alerting system |
| `TrustScoreComputedAt` | You need a lightweight notification that a run completed — e.g. a logger or a cache invalidator that doesn't need the scores themselves |

`TrustScoreRoutingPublisher` detects which payload types have registered observers at startup
(via `BeanManager`) and skips the work for unregistered types entirely — including the
pre-read of previous scores needed for delta computation.

## CDI fire vs fireAsync

CDI 4.x has two separate delivery paths:

- `fire()` reaches `@Observes` observers — synchronous, on the caller's thread
- `fireAsync()` reaches `@ObservesAsync` observers — asynchronous, on a separate thread

They do not cross. An `@Observes` method will not receive an event fired with `fireAsync()`,
and vice versa. `TrustScoreComputedAt` is fired on both paths so consumers can choose either
delivery mode. `TrustScoreFullPayload` and `TrustScoreDeltaPayload` use `fire()` only.

## What this example shows

**`TaskRouter`** — sync observer that builds a ranked agent list on every full recompute:

```java
@ApplicationScoped
public class TaskRouter {

    private volatile List<String> rankedAgents = List.of();

    public void onScoresUpdated(@Observes TrustScoreFullPayload payload) {
        rankedAgents = payload.scores().stream()
                .sorted(Comparator.comparingDouble(s -> -s.trustScore))
                .map(s -> s.actorId)
                .toList();
    }

    public List<String> getRankedAgents() {
        return new ArrayList<>(rankedAgents);
    }
}
```

**`RoutingSignalLogger`** — async observer that logs the refresh signal without blocking the
job thread:

```java
@ApplicationScoped
public class RoutingSignalLogger {

    public CompletionStage<Void> onNotification(
            @ObservesAsync TrustScoreComputedAt notification) {
        log.infof("Trust scores refreshed at %s for %d actors",
                notification.computedAt(), notification.actorCount());
        return CompletableFuture.completedFuture(null);
    }
}
```

**`GET /routing/ranked-agents`** returns the current ranked list held in `TaskRouter`. The
list is populated once at the first trust score computation run; before that it is empty.

## Running the example

```bash
cd examples/trust-score-routing
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn quarkus:dev
```

```bash
# Query the ranked agents after a trust score computation has run
curl http://localhost:8080/routing/ranked-agents
```

## Running the tests

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test
```
