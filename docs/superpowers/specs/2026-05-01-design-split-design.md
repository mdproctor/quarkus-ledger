# DESIGN.md Split — Spec

**Date:** 2026-05-01
**Status:** Approved

## Problem

`docs/DESIGN.md` is 503 lines. Group B (#60–#62: capabilityTag, capability-scoped trust,
multi-dimensional trust) will add substantially to the algorithms/capabilities sections.
The file needs splitting before that work begins.

## Organizing Principle

**Stable structure** vs. **growing algorithms/capabilities.**

- Stable structure: entity model, JOINED inheritance, supplements, SPI contracts, Flyway
  conventions, configuration, scope, roadmap, tracker. Changes infrequently once Group A
  is done.
- Algorithms/capabilities: Merkle MMR, W3C PROV-DM, trust scoring, agent identity, agent
  mesh. Group B lands here; this is where most future growth occurs.

## Approach: Two files, DESIGN.md keeps stable content

### File structure

| File | Approx lines | Content |
|---|---|---|
| `docs/DESIGN.md` | ~280 | Purpose, Ecosystem Context, Architecture, Supplements, Key Design Decisions, Configuration, Scope, Roadmap, Implementation Tracker |
| `docs/DESIGN-capabilities.md` | ~230 + Group B | Merkle MMR, W3C PROV-DM, Agent Identity Model, Agent Mesh Topology |

### Changes to DESIGN.md

1. Sections moved out: Merkle Mountain Range, W3C PROV-DM JSON-LD Export, Agent Identity
   Model, Agent Mesh Topology — moved verbatim, no rewrites.
2. One new section inserted after Ecosystem Context, before Architecture:

```markdown
## Further Reading

| Document | What it covers |
|---|---|
| [`DESIGN-capabilities.md`](DESIGN-capabilities.md) | Merkle Mountain Range, W3C PROV-DM JSON-LD export, agent identity model, agent mesh topology |
```

### New file: DESIGN-capabilities.md

Opens with:

```markdown
# CaseHub Ledger — Capabilities Design

> Part of the casehub-ledger design documentation. See [`DESIGN.md`](DESIGN.md) for
> entity model, architecture, SPI contracts, and configuration.
```

Followed by the four moved sections in their current order.

## What Does Not Change

- All section content is moved verbatim — no rewrites.
- No anchor fragment links need updating (CLAUDE.md does not link to fragments).
- `docs/DESIGN.md` remains the primary entry point; zero link breakage.

## Out of Scope

- Splitting DESIGN-capabilities.md further (e.g. trust vs. integrity) — deferred until that
  file reaches a similar inflection point.
- Rewriting or reorganising any section content — pure structural move only.
