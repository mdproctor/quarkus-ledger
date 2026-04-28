# ADR 0005 — LedgerEntry Enricher SPI

**Date:** 2026-04-28
**Status:** Accepted
**Refs:** #67

## Context

`LedgerTraceListener` auto-populated `traceId` via `@PrePersist`. Issue #59 was about
to add a second `@PrePersist` mechanism for provenance capture. Two separate listeners
for the same lifecycle concern would diverge, be applied inconsistently, and confuse
future contributors.

## Decision

Replace the monolithic `@PrePersist` body with a pluggable `LedgerEntryEnricher` SPI.
`LedgerTraceListener` becomes a non-fatal pipeline runner that iterates
`Instance<LedgerEntryEnricher>`. Any module can contribute enrichers by implementing
the interface as a CDI bean — no registration step required.

## Native Image

Quarkus's ArC CDI container processes `@ApplicationScoped` beans and
`Instance<LedgerEntryEnricher>` injection at build time. No explicit reflection
configuration is required — ArC handles discovery and registration for native image
compilation.

## Consequences

- `LedgerTraceListener` is now a thin pipeline runner; all enrichment logic lives in
  enricher beans.
- Enricher failures are logged and swallowed — the persist is never blocked.
- Enricher ordering is CDI-discovery order (unspecified). Enrichers must not depend on
  execution order.
- `#59` (ProvenanceSupplement) becomes a `ProvenanceCaptureEnricher` — one mechanism
  for all field auto-population at persist time.
