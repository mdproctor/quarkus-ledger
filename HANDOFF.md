# Quarkus Ledger — Session Handover
**Date:** 2026-04-22

## Current State

*Unchanged — `git show HEAD~1:HANDOFF.md`*

## What Landed This Session

**`correlationId` → `traceId` rename (Closes #30)**
- Field was always OTel-only; `correlationId` is an established messaging term (JMS/AMQP request-reply)
- Renamed across entity, migration column/index, archiver, PROV serializer, all tests
- `causedByEntryId` already covers message-level correlation — no gap

**OTel `traceId` auto-wiring (Closes #31)**
- `LedgerTraceListener` — `@ApplicationScoped` JPA entity listener; `@PrePersist` populates `traceId` if null
- `LedgerTraceIdProvider` SPI — consumers can override; default reads `Span.current()`
- `OtelTraceIdProvider` — `@DefaultBean`; safe when no OTel SDK (noop span → `isValid()=false` → stays null)
- `opentelemetry-api` added as optional Maven dep (was already transitively present)
- `@DefaultBean` gotcha: lives in `io.quarkus.arc`, not `jakarta.enterprise.inject`

**Integration test for CDI entity listener wire-up**
- `LedgerTraceListenerIT` — 3 `@QuarkusTest` cases: auto-populated, caller-set not overwritten, null when no span
- `Span.wrap(SpanContext).makeCurrent()` used for OTel context without SDK
- 175 tests, all green

## Immediate Next Steps

No open issues. Candidates from DESIGN.md Roadmap:
1. **Quarkiverse submission prep** — API stabilisation decision needed
2. **CaseHub consumer** — depends on CaseHub work

## References

| What | Path |
|---|---|
| Design doc | `docs/DESIGN.md` |
| Latest blog | `blog/2026-04-22-mdp12-trace-id-entity-listener-gap.md` |
| GitHub repo | mdproctor/quarkus-ledger (0 open issues) |
