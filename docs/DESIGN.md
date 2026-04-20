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
  └── agent_message_ledger_entry ← quarkus-qhorus (V1004+ in Qhorus)
```

`LedgerAttestation` references `ledger_entry.id` directly — attestations work
for any subclass without any changes. `ActorTrustScore` references `actorId`
from base entries — trust scoring works across all consumers.

### Base Tables (created by this extension)

| Table | Migration | Purpose |
|---|---|---|
| `ledger_entry` | V1000 | Base audit record (discriminator column: `dtype`) |
| `ledger_attestation` | V1000 | Peer verdicts — FK to `ledger_entry.id` |
| `actor_trust_score` | V1001 | Nightly Bayesian Beta trust scores per actor |
| `ledger_supplement_compliance` | V1002 | ComplianceSupplement joined table |
| `ledger_supplement_provenance` | V1002 | ProvenanceSupplement joined table |
| `ledger_entry_archive` | V1003 | Archive records before retention deletion |
| `ledger_merkle_frontier` | V1000 | Merkle Mountain Range frontier nodes (≤log₂(N) rows per subject) |

---

## Supplements

A **supplement** is an optional, lazily-loaded extension to a `LedgerEntry` that carries
a named group of cross-cutting fields. Supplements live in separate joined tables and are
never written unless the consumer explicitly attaches one — consumers that do not use
supplements incur zero schema or runtime cost.

### Why supplements?

`LedgerEntry` is the shared base for every consumer in the ecosystem. Adding optional
fields directly to the base entity creates a wide-table anti-pattern: every consumer
sees fields that are irrelevant to their use case, with no signal about which fields
belong together or when to populate them. Supplements solve this by grouping optional
fields by concern and moving them out of the core entity entirely.

### Built-in supplements

| Supplement | Table | Fields | Use when |
|---|---|---|---|
| `ComplianceSupplement` | `ledger_supplement_compliance` | `planRef`, `rationale`, `evidence`, `detail`, `decisionContext`, `algorithmRef`, `confidenceScore`, `contestationUri`, `humanOverrideAvailable` | Recording automated decisions subject to GDPR Art.22 or EU AI Act Art.12 |
| `ProvenanceSupplement` | `ledger_supplement_provenance` | `sourceEntityId`, `sourceEntityType`, `sourceEntitySystem` | Entry is driven by an external workflow system |

### Attaching a supplement

```java
ComplianceSupplement cs = new ComplianceSupplement();
cs.algorithmRef = "risk-model-v3";
cs.confidenceScore = 0.91;
cs.contestationUri = "https://example.com/challenge/" + entry.id;
cs.humanOverrideAvailable = true;
entry.attach(cs); // also refreshes entry.supplementJson
```

### Reading supplement data

**Fast path (single entry, no join):** Read `entry.supplementJson` — a JSON blob
written by `attach()` containing all attached supplements. No additional query.

**Typed access (lazy join):** Use the typed accessors — `entry.compliance()`,
`entry.provenance()`. Triggers a single SELECT on the supplement table only when
accessed.

### Zero-complexity guarantee

If a consumer never calls `attach()`, no supplement table rows are written and the
lazy `supplements` list is never initialised. Consumers already integrated with
`quarkus-ledger` require zero changes.

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

| Range | Owner | Purpose |
|---|---|---|
| V1000–V1003 | `quarkus-ledger` base | Base schema (reserved — do not use in consumers) |
| V1–V999 | Consumer | Domain tables (orders, cases, channels, etc.) |
| V1004+ | Consumer | Subclass join tables (must run after V1000 — FK constraint) |

This ordering is not optional. The subclass join table has
`FOREIGN KEY ... REFERENCES ledger_entry (id)`. A subclass migration numbered below
V1000 will fail with `Table "LEDGER_ENTRY" not found` because Flyway merges all
classpath migrations globally and sorts by version number.

### Hash chain canonical form

`subjectId|seqNum|entryType|actorId|actorRole|occurredAt`

`planRef` was removed from the canonical form — it now lives in `ComplianceSupplement`.
Supplement fields are deliberately excluded from the chain: the chain covers the immutable
core audit record; compliance metadata is enrichment, not a tamper-evidence target.

Deliberately excludes subclass-specific fields (`commandType`, `eventType`, `toolName`,
etc.). The chain covers provenance and timing; domain labels do not participate in
tamper detection. This keeps the chain domain-agnostic — the same `LedgerMerkleTree`
utility works for any subclass.

## Merkle Mountain Range

Replaces the linear hash chain. Per-subject stored frontier gives O(log N) inclusion proofs.

**Hash functions (RFC 9162 domain separation):**
- Leaf: `SHA-256(0x00 | subjectId|seqNum|entryType|actorId|actorRole|occurredAt)`
- Internal node: `SHA-256(0x01 | left_bytes | right_bytes)` — raw 32-byte values, not hex

**Frontier:** `ledger_merkle_frontier` table stores at most `Integer.bitCount(N)` rows per subject after N entries. The tree root = fold frontier ASC by level.

**`LedgerMerkleTree`** (pure static utility) — `leafHash()`, `internalHash()`, `append()`, `treeRoot()`, `inclusionProof()`, `verifyProof()`. No CDI, no side effects.

**`LedgerVerificationService`** (`@ApplicationScoped`) — `treeRoot(UUID)`, `inclusionProof(UUID)`, `verify(UUID)`. Auto-activated.

**External publishing** (opt-in) — `LedgerMerklePublisher` posts Ed25519-signed tlog-checkpoints to `quarkus.ledger.merkle.publish.url` on each frontier update. Disabled by default.

## W3C PROV-DM JSON-LD Export

Exports a subject's complete audit trail as a W3C PROV-DM JSON-LD document for
interoperability with ML pipeline auditing tools, RDF stores, and regulatory systems.

**Mapping:**
- `LedgerEntry` → `prov:Entity` (`ledger:entry/<uuid>`)
- `actorId` → `prov:Agent` (`ledger:actor/<actorId>`, deduplicated per export)
- Entry action → `prov:Activity` (`ledger:activity/<uuid>`)

**Relations:** `wasGeneratedBy` (every entry), `wasAssociatedWith` (when actorId set),
`wasDerivedFrom` (sequential chain + `causedByEntryId` cross-subject causality),
`hadPrimarySource` (when `ProvenanceSupplement` attached).

**`LedgerProvSerializer`** (pure static) — `toProvJsonLd(UUID subjectId, List<LedgerEntry> entries)`.
No CDI, no DB access. Must be called within a `@Transactional` boundary so supplements lazy-load.

**`LedgerProvExportService`** (`@ApplicationScoped`) — `exportSubject(UUID subjectId)`.
Fetches entries, initialises supplements, delegates to serialiser. Auto-activated.

See `docs/prov-dm-mapping.md` for the full field-by-field mapping including all supplement fields.

### `@ConfigRoot` alongside `@ConfigMapping`

`LedgerConfig` carries both annotations. `@ConfigMapping` provides the SmallRye nested
interface API; `@ConfigRoot(phase = ConfigPhase.RUN_TIME)` tells the
`quarkus-extension-processor` to emit the `quarkus.ledger` prefix into the extension
descriptor. Without `@ConfigRoot`, consuming apps see "Unrecognized configuration key"
warnings and cannot override defaults via `application.properties`.

---

## Configuration

The extension is configured under the `quarkus.ledger` prefix via `application.properties` or environment variables.

**Core settings (`quarkus.ledger.*`):**

| Key | Default | Description |
|---|---|---|
| `enabled` | `true` | Master switch; disables all ledger operations when false |
| `hash-chain.enabled` | `true` | Enable SHA-256 hash chaining for tamper detection |
| `decision-context.enabled` | `true` | Capture decision context snapshots (GDPR Art.22 / EU AI Act Art.12) |
| `evidence.enabled` | `false` | Accept and store structured evidence in `ComplianceSupplement.evidence` |
| `attestations.enabled` | `true` | Enable peer attestation endpoints |
| `trust-score.enabled` | `false` | Enable nightly Bayesian Beta trust score computation (requires historical data) |
| `trust-score.decay-half-life-days` | `90` | Exponential decay half-life for attestation recency weighting |
| `trust-score.routing-enabled` | `false` | Influence routing via CDI events based on trust scores |

**Retention sub-config (`quarkus.ledger.retention.*`):**

| Key | Default | Description |
|---|---|---|
| `retention.enabled` | `false` | Enable retention enforcement — off by default, zero behaviour change when disabled |
| `retention.operational-days` | `180` | Retention window in days (EU AI Act Art.12 minimum: 6 months) |
| `retention.archive-before-delete` | `true` | Write full entry JSON to `ledger_entry_archive` before deletion |

**Merkle publishing sub-config (`quarkus.ledger.merkle.publish.*`):**

| Key | Default | Description |
|---|---|---|
| `merkle.publish.url` | (absent) | POST endpoint for Ed25519-signed tlog-checkpoints; publisher inactive when absent |
| `merkle.publish.private-key` | (absent) | Path to Ed25519 private key PEM file (PKCS#8) |
| `merkle.publish.key-id` | `"default"` | Opaque key identifier included in each checkpoint |

Archive-then-delete: verify chain integrity → write to `ledger_entry_archive` → delete attestations → JPA-cascade delete entry. A subject with a broken hash chain is skipped.
Audit queries: `findByActorId(actorId, from, to)`, `findByActorRole(role, from, to)`, `findByTimeRange(from, to)` — all use `Instant` params for timezone-safe querying.

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

**Trust scoring** uses a Bayesian Beta model. For each actor, all attestations across all
decisions accumulate into a Beta distribution: `α` for positive verdicts (SOUND, ENDORSED),
`β` for negative verdicts (FLAGGED, CHALLENGED). Each contribution is recency-weighted:
`weight = 2^(-ageInDays / decayHalfLifeDays)` using the attestation's own timestamp.
Prior is Beta(1,1) → score 0.5 with no history. Score = α/(α+β).

`ActorTrustScore` stores `trust_score`, `alpha_value`, `beta_value`, and diagnostic
counters. `TrustScoreJob` runs nightly when enabled.

**Privacy / pseudonymisation** — Axiom 7 gap, GDPR right-to-erasure design (pending).

### Medium-term

**Quarkiverse submission** — structurally ready (quarkiverse-parent, CI workflows,
full docs, 127 tests across runtime + examples). Needs a stability decision on the
public API (`LedgerEntry` core fields, `LedgerMerkleTree` canonical form, supplement API)
before submitting. The supplement architecture stabilises the surface — `attach()`,
`compliance()`, `provenance()` are the public entry points.

**OTel trace ID auto-wiring** — automatically populate `correlationId` from the active
OTel span context. Could be provided as a base helper that capture services call, or
wired directly in the extension using a CDI extension observer.

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
| **Initial extraction** | ✅ Done | Abstract LedgerEntry, LedgerAttestation, ActorTrustScore, LedgerMerkleTree, TrustScoreComputer, TrustScoreJob, SPI, LedgerConfig, Flyway V1000/V1001, jandex, @Alternative, @ConfigRoot |
| **Unit tests** | ✅ Done | 42 unit tests — LedgerMerkleTree (22, LedgerMerkleTreeTest) + TrustScoreComputer (16) + LedgerSupplementSerializer (8) — then extended to 127 with Merkle, publisher, PROV-DM, and IT tests |
| **Tarkus migration** | ✅ Done | WorkItemLedgerEntry, WorkItemLedgerEntryRepository, Tarkus-ledger 69 tests passing |
| **Documentation** | ✅ Done | README, integration guide, examples.md, AUDITABILITY.md, RESEARCH.md |
| **Runnable examples** | ✅ Done | `examples/order-processing/` (12 IT), `examples/art22-decision-snapshot/` (3 IT), `examples/art12-compliance/` (3 IT), `examples/merkle-verification/` (2 IT), `examples/prov-dm-export/` (2 IT) |
| **LedgerSupplement architecture** | ✅ Done | ComplianceSupplement, ProvenanceSupplement, ObservabilitySupplement deleted (fields moved to core); LedgerEntry with 12 core fields; Flyway V1000/V1001; 7 supplement IT tests; GDPR Art.22 example |
| **Forgiveness mechanism** | ✅ Superseded | Replaced by Bayesian Beta model (ADR 0003). ForgivenessParams removed. |
| **EU AI Act Art.12 compliance** | ✅ Done | Archive-then-delete retention job (`LedgerRetentionJob`), V1003 archive table, audit query SPI (`findByActorId`, `findByActorRole`, `findByTimeRange`), `docs/compliance/EU-AI-ACT-ART12.md`, `examples/art12-compliance/` |
| **Causality & Observability to core** | ✅ Done | `correlationId` + `causedByEntryId` on `LedgerEntry`; `ObservabilitySupplement` deleted; `findCausedBy()` SPI |
| **Merkle Mountain Range** | ✅ Done | `LedgerMerkleTree` (RFC 9162 MMR), `LedgerMerkleFrontier` (log₂(N) rows/subject), `LedgerVerificationService` (treeRoot/inclusionProof/verify), `LedgerMerklePublisher` (opt-in Ed25519 tlog-checkpoint); ADR 0002; `examples/merkle-verification/` (2 IT) |
| **W3C PROV-DM JSON-LD export** | ✅ Done | `LedgerProvSerializer.toProvJsonLd()` (pure static, 13 unit tests), `LedgerProvExportService` (CDI bean, 4 IT); `docs/prov-dm-mapping.md` field reference; `examples/prov-dm-export/` (2 IT) |
| **Bayesian trust weighting** | ✅ Done | Bayesian Beta model: per-attestation recency weighting, alpha/beta posterior, ForgivenessParams removed. See ADR 0003. |
| **Quarkiverse submission** | ⬜ Pending | API stabilisation + submission PR |
| **OTel correlation wiring** | ⬜ Pending | Auto-populate correlationId from active span |
| **CaseHub consumer** | ⬜ Pending | Depends on CaseHub integration work |
