# Quarkus Ledger — Session Handover
**Date:** 2026-04-15 (first session)

## Current State

- **Tests:** 33 unit tests passing, 0 failing
- **Examples:** `examples/order-processing/` — 8 IT tests green (`mvn test`)
- **Open issues:** none
- **Version:** 1.0.0-SNAPSHOT (installed to local Maven repo)
- **Flyway:** V1000/V1001 (base schema)

## Immediate Next Step

No active work. Extension is feature-complete for current consumers (Tarkus + Qhorus).

When resuming: check if CaseHub integration has started — that will surface new
requirements. Otherwise, the next milestone is Quarkiverse submission (see `docs/DESIGN.md`
§ Roadmap for what needs to happen first).

## References

| What | Path |
|---|---|
| Design doc (architecture, decisions, roadmap) | `docs/DESIGN.md` |
| Integration guide | `docs/integration-guide.md` |
| Worked examples (code) | `docs/examples.md` |
| Runnable example app | `examples/order-processing/` |
| Day Zero diary entry | `blog/2026-04-15-mdp01-shared-audit-ledger-ecosystem.md` |
| Tarkus subclass (reference) | `~/claude/quarkus-tarkus/quarkus-tarkus-ledger/` |
| Qhorus subclass (reference) | `~/claude/quarkus-qhorus/runtime/.../ledger/` |
