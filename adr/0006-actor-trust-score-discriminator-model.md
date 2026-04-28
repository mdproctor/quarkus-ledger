# ADR 0006 — ActorTrustScore Discriminator Model

**Date:** 2026-04-28
**Status:** Accepted
**Refs:** #68

## Context

Issues #61 (capability-scoped trust) and #62 (multi-dimensional trust) were both about
to add nullable columns to `actor_trust_score`. Two separate nullable scope keys on the
same table is messy, hard to query, and closed to extension. A unified discriminator
model was designed before either feature begins.

## Decision

Replace the single-row-per-actor model with a `(actor_id, score_type, scope_key)` discriminator:

- Surrogate UUID `id` as primary key (actor_id is no longer globally unique)
- `score_type` enum: GLOBAL | CAPABILITY | DIMENSION
- `scope_key` nullable: NULL for GLOBAL, tag string for CAPABILITY, dimension name for DIMENSION
- `UNIQUE NULLS NOT DISTINCT (actor_id, score_type, scope_key)` — enforces that two GLOBAL rows
  for the same actor are a constraint violation, even though scope_key is NULL

`NULLS NOT DISTINCT` is supported in PostgreSQL 15+ and H2 2.4.240+ (the version pulled by
Quarkus 3.32.2). No sentinel value is needed.

## Rejected alternative: empty string sentinel for GLOBAL scope_key

Using `scope_key = ''` for GLOBAL avoids the NULL constraint issue but is semantically
misleading and carries a maintenance burden. Checking library versions revealed
`NULLS NOT DISTINCT` is available in all target databases — no workaround needed.

## Consequences

- All existing `findByActorId` callers continue to work — the method returns the GLOBAL row.
- #61 and #62 add computation logic only — the schema and query infrastructure are already in place.
- Adding a new score type (e.g. TEMPORAL) requires only a new `ScoreType` enum value — no schema change.
- EigenTrust (`globalTrustScore`) is only meaningful on GLOBAL rows; documented in Javadoc.
- `TrustScoreRoutingPublisher` currently keys snapshots by `actorId` — must be updated when #61 ships CAPABILITY rows (tracked in TODO comment on `TrustScoreJob`).
