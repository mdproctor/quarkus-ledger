# Quarkus Ledger — Session Handover
**Date:** 2026-04-15 (first session — created from scratch)

## What This Project Is

`quarkus-ledger` is the shared audit/provenance foundation for the Quarkus Native AI Ecosystem.
It was extracted from `quarkus-tarkus-ledger` and generalised so that Tarkus, Qhorus, and
future consumers (CaseHub) each extend it with a domain-specific JPA subclass rather than
duplicating the same patterns.

**Current consumers:**
| Consumer | Subclass | `subjectId` maps to |
|---|---|---|
| `quarkus-tarkus` | `WorkItemLedgerEntry` | WorkItem UUID |
| `quarkus-qhorus` | `AgentMessageLedgerEntry` | Channel UUID |

**Where it lives in the ecosystem:**
```
quarkus-ledger        (audit/provenance — this project)
    ↑         ↑
 tarkus    qhorus    (each adds its own LedgerEntry subclass)
    ↑         ↑
          claudony  (integration layer — Phase 8 pending)
```

---

## Current State

- **Build:** clean (`mvn clean install` green)
- **Tests:** 33 unit tests (LedgerHashChain + TrustScoreComputer), 0 failures
- **Examples:** `examples/order-processing/` — 8 IT tests, `mvn test` green
- **Open issues:** none
- **Flyway migrations:** V1000 (ledger_entry + ledger_attestation), V1001 (actor_trust_score)
- **Version:** 1.0.0-SNAPSHOT — not yet submitted to Quarkiverse

---

## Critical Design Decisions (read before changing anything)

**`subjectId` — the aggregate identifier**
Replaces the Tarkus-specific `workItemId`. All queries, sequence numbers, and the hash chain
are scoped per `subjectId`. Consumers set it to their own domain aggregate UUID.

**JPA JOINED inheritance**
`LedgerEntry` is `abstract` with `@Inheritance(strategy = JOINED)`. Each consumer adds its own
table joining on `id`. The base `ledger_entry` table holds the audit fields; domain fields live
in subclass tables. `LedgerAttestation` references `ledger_entry.id` — attestations work for
any subclass without changes.

**`JpaLedgerEntryRepository` is `@Alternative`**
Without this, when a consumer provides its own typed repo (e.g. `JpaWorkItemLedgerEntryRepository`),
CDI sees two beans implementing `LedgerEntryRepository` and fails. `@Alternative` means the base
impl yields automatically. **Consequence:** in a standalone deployment with no domain-specific repo,
the application must explicitly activate `JpaLedgerEntryRepository` via `beans.xml`. No consumer
has needed this yet because all current consumers provide their own repo.

**Flyway version numbering convention**
- Base schema: V1000 + V1001 (defined here)
- Consumer subclass tables: V1002+ (defined by the consumer, never by this extension)
- Domain tables in consumer apps: V1–V999
This ordering ensures the base `ledger_entry` table exists before any subclass FK references it.

**Hash chain canonical form**
`subjectId|seqNum|entryType|actorId|actorRole|planRef|occurredAt`
Deliberately excludes subclass-specific fields (e.g. `commandType`, `eventType` in Tarkus).
The chain covers provenance and timing; domain labels are not part of tamper detection.

**`@ConfigRoot` + `@ConfigMapping` together**
`LedgerConfig` uses both annotations. `@ConfigMapping` provides the SmallRye nested interface API;
`@ConfigRoot` registers the `quarkus.ledger` prefix in the extension descriptor so consuming apps
don't see "Unrecognized configuration key" warnings.

---

## What Is Deliberately NOT in This Extension

These are out of scope by design — consumers implement their own:

- **REST endpoints** — no `/ledger` resource in the base. Each consumer (Tarkus, Qhorus) defines
  its own endpoints suited to its domain path and auth model.
- **MCP tools** — no `@Tool` methods in the base. Qhorus adds `list_events` / `get_channel_timeline`
  as MCP tools; Tarkus has its own REST surface.
- **CDI events / observers** — no `WorkItemLifecycleEvent` equivalent. Each consumer wires its own
  capture service to its domain events.
- **OTel trace ID auto-wiring** — `correlationId` field exists and is populated manually by consumers.
  Automatic wiring from the OTel context propagation is a future enhancement.

---

## Roadmap

**Near-term (next session):**
- Nothing blocking — the extension is feature-complete for current consumers
- Wait for CaseHub integration to surface requirements before adding anything

**Medium-term:**
- **Quarkiverse submission** — structurally ready (quarkiverse-parent, proper CI workflows, docs,
  tests). Needs a stabilisation decision on the public API (mainly `LedgerEntry` field names and
  `LedgerHashChain` canonical form) before submitting. Neither has changed externally since creation.
- **OTel trace ID wiring** — automatically populate `correlationId` from the active OTel span context.
  Simple to add in `LedgerWriteService`-style capture services, or could be provided as a base helper.
- **Hash chain verification endpoint** — `LedgerHashChain.verify()` exists and works. Providing a
  base REST resource or MCP tool that exposes it would remove copy-paste across consumers.

**Longer-term (depends on CaseHub):**
- **CaseHub consumer** — CaseHub will likely add a `CaseLedgerEntry` subclass. The pattern is
  established; the implementation is straightforward.
- **`@Alternative` activation helper** — for standalone deployments that don't provide a domain
  repo, document (or provide) the `beans.xml` activation approach.
- **Trust score routing signals** — `quarkus.ledger.trust-score.routing-enabled` is wired in config
  but not yet implemented. When enabled, it should fire CDI events that routing layers can observe.

---

## README Assessment

The README (`README.md`) is solid for developers *using* the extension. A new Claude picking
up this project would be missing:

1. **Ecosystem context** — the README has no "why does this exist" or "who depends on it" section.
   The CLAUDE.md has this but a new Claude reading only README would not understand the role.
2. **`@Alternative` CDI gotcha** — critical for anyone trying to use the base repo directly in a
   standalone app. Not mentioned in README or integration guide.
3. **What's NOT in scope** — a new Claude might add REST endpoints to the base extension,
   not knowing this is intentionally deferred to consumers.
4. **Quarkiverse submission aspiration** — currently mentioned nowhere.

Consider adding a short "Ecosystem Context" section and a "Design Constraints" or "Scope" note
to the README before submission.

---

## References

| What | Path |
|---|---|
| Integration guide | `docs/integration-guide.md` |
| Worked examples (code) | `docs/examples.md` |
| Runnable example app | `examples/order-processing/` |
| Day Zero diary entry | `blog/2026-04-15-mdp01-shared-audit-ledger-ecosystem.md` |
| Tarkus subclass (reference impl) | `~/claude/quarkus-tarkus/quarkus-tarkus-ledger/` |
| Qhorus subclass (reference impl) | `~/claude/quarkus-qhorus/runtime/src/main/java/io/quarkiverse/qhorus/runtime/ledger/` |
