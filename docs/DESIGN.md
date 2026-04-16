# Quarkus Ledger — Design Document

## Purpose

`quarkus-ledger` is the shared audit/provenance foundation for the Quarkus Native AI
Ecosystem. It was extracted from `quarkus-tarkus-ledger` and generalised so that
Tarkus, Qhorus, and future consumers (CaseHub) each extend it with a domain-specific
JPA subclass rather than duplicating the same patterns.

The extension is intentionally thin: it provides the base entity, hash chain, trust
score algorithm, SPI, and configuration. REST endpoints, MCP tools, and CDI capture
services are deliberately deferred to consumers — each domain knows its own path,
auth model, and event system better than a shared base can.

---

## Ecosystem Context

```
quarkus-ledger        (audit/provenance — this project)
    ↑         ↑         ↑
 tarkus    qhorus    casehub    (each adds its own LedgerEntry subclass)
    ↑         ↑
          claudony
```

**Current consumers:**

| Consumer | Subclass | `subjectId` maps to | Added fields |
|---|---|---|---|
| `quarkus-tarkus` | `WorkItemLedgerEntry` | WorkItem UUID | `commandType`, `eventType` |
| `quarkus-qhorus` | `AgentMessageLedgerEntry` | Channel UUID | `toolName`, `durationMs`, `tokenCount`, `contextRefs`, `sourceEntity` |

---

## Architecture

### JPA JOINED Inheritance

`LedgerEntry` is abstract with `@Inheritance(strategy = JOINED)`. The base
`ledger_entry` table holds all common audit fields. Each consumer adds a sibling
table joining on `id`.

```
ledger_entry (base — V1000)
  ├── work_item_ledger_entry     ← quarkus-tarkus (V100 in Tarkus)
  └── agent_message_ledger_entry ← quarkus-qhorus (V1002 in Qhorus)
```

`LedgerAttestation` references `ledger_entry.id` directly — attestations work
for any subclass without any changes. `ActorTrustScore` references `actorId`
from base entries — trust scoring works across all consumers.

### Base Tables (created by this extension)

| Table | Migration | Purpose |
|---|---|---|
| `ledger_entry` | V1000 | Base audit record (discriminator column: `dtype`) |
| `ledger_attestation` | V1000 | Peer verdicts — FK to `ledger_entry.id` |
| `actor_trust_score` | V1001 | Nightly EigenTrust scores per actor |

---

## Key Design Decisions

### `subjectId` — the generic aggregate identifier

Replaces the Tarkus-specific `workItemId`. All queries, sequence numbers, and the
hash chain are scoped per `subjectId`. Consumers set it to their domain aggregate UUID.
The base extension has no opinion on what aggregates are.

### `JpaLedgerEntryRepository` is `@Alternative`

Without this, when a consumer provides its own typed repository (e.g.
`JpaWorkItemLedgerEntryRepository`), CDI sees two beans implementing
`LedgerEntryRepository` and fails at startup. `@Alternative` means the base
implementation yields automatically when a domain-specific one is present.

**Consequence for standalone use:** In a deployment with no domain-specific repo, the
application must explicitly activate `JpaLedgerEntryRepository` via `beans.xml`. No
current consumer has needed this — all provide their own typed repo.

### Flyway version numbering convention

- V1000–V1001: base schema (defined here — reserved)
- V1–V999: consumer domain tables (safe range for application schemas)
- V1002+: consumer subclass join tables (must run after V1000 due to FK constraint)

This ordering is not optional. The subclass join table has
`FOREIGN KEY ... REFERENCES ledger_entry (id)`. A subclass migration numbered below
V1000 will fail with `Table "LEDGER_ENTRY" not found` because Flyway merges all
classpath migrations globally and sorts by version number.

### Hash chain canonical form

`subjectId|seqNum|entryType|actorId|actorRole|planRef|occurredAt`

Deliberately excludes subclass-specific fields (`commandType`, `eventType`, `toolName`,
etc.). The chain covers provenance and timing; domain labels do not participate in
tamper detection. This keeps the chain domain-agnostic — the same `LedgerHashChain`
utility works for any subclass.

### `@ConfigRoot` alongside `@ConfigMapping`

`LedgerConfig` carries both annotations. `@ConfigMapping` provides the SmallRye nested
interface API; `@ConfigRoot(phase = ConfigPhase.RUN_TIME)` tells the
`quarkus-extension-processor` to emit the `quarkus.ledger` prefix into the extension
descriptor. Without `@ConfigRoot`, consuming apps see "Unrecognized configuration key"
warnings and cannot override defaults via `application.properties`.

---

## What Is Deliberately Out of Scope

These are excluded by design — consumers implement their own:

| Capability | Why excluded |
|---|---|
| REST endpoints | Each domain has its own path structure, auth model, and response shape |
| MCP tools | Domain-specific; Qhorus adds `list_events`, Tarkus has its own REST surface |
| CDI capture observers | Each consumer wires its own service to its own domain events |
| OTel trace ID auto-wiring | `correlationId` field exists; auto-population from OTel context is a future enhancement, left to consumers for now |
| Event replay / CQRS projections | The ledger is an append-only audit record, not a source of truth for domain state |

---

## Roadmap

### Near-term

Nothing blocking. Extension is feature-complete for current consumers. Wait for
CaseHub integration requirements before adding anything to the base.

### Medium-term

**Quarkiverse submission** — structurally ready (quarkiverse-parent, CI workflows,
full docs, 33 unit tests, runnable example). Needs a stability decision on the public
API (`LedgerEntry` field names, `LedgerHashChain` canonical form) before submitting.
Neither has changed externally since creation.

**OTel trace ID auto-wiring** — automatically populate `correlationId` from the active
OTel span context. Could be provided as a base helper that capture services call, or
wired directly in the extension using a CDI extension observer.

**Hash chain verification helper** — `LedgerHashChain.verify()` works but consumers
must expose it themselves. A base utility (REST endpoint or service method) that
consumers can opt into would reduce copy-paste.

### Longer-term (depends on CaseHub)

**CaseHub consumer** — CaseHub will likely add a `CaseLedgerEntry` subclass covering
orchestration workflow transitions. Pattern is established; implementation follows
the Tarkus/Qhorus examples.

**`@Alternative` activation documentation** — for standalone deployments that provide
no domain repo, document (or provide) the `beans.xml` activation path.

**Trust score routing signals** — `quarkus.ledger.trust-score.routing-enabled` is wired
in config but not implemented. When enabled it should fire CDI events that routing layers
(e.g. CaseHub task assignment) can observe to prefer high-trust actors.

---

## Implementation Tracker

| Phase | Status | What |
|---|---|---|
| **Initial extraction** | ✅ Done | Abstract LedgerEntry, LedgerAttestation, ActorTrustScore, LedgerHashChain, TrustScoreComputer, TrustScoreJob, SPI, LedgerConfig, Flyway V1000/V1001, jandex, @Alternative, @ConfigRoot |
| **Unit tests** | ✅ Done | 33 tests — LedgerHashChain (17) + TrustScoreComputer (16) |
| **Tarkus migration** | ✅ Done | WorkItemLedgerEntry, WorkItemLedgerEntryRepository, Tarkus-ledger 69 tests passing |
| **Documentation** | ✅ Done | README, integration guide, examples.md |
| **Runnable example** | ✅ Done | examples/order-processing/ — 8 IT tests, mvn quarkus:dev |
| **Quarkiverse submission** | ⬜ Pending | API stabilisation + submission PR |
| **OTel correlation wiring** | ⬜ Pending | Auto-populate correlationId from active span |
| **CaseHub consumer** | ⬜ Pending | Depends on CaseHub integration work |
