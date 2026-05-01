# CaseHub Ledger — Design Document

## Purpose

`casehub-ledger` is the shared audit/provenance foundation for the Quarkus Native AI
Ecosystem. It was extracted from `casehub-ledger` and generalised so that
Tarkus, Qhorus, and future consumers (CaseHub) each extend it with a domain-specific
JPA subclass rather than duplicating the same patterns.

The extension is intentionally thin: it provides the base entity, hash chain, trust
score algorithm, SPI, and configuration. REST endpoints, MCP tools, and CDI capture
services are deliberately deferred to consumers — each domain knows its own path,
auth model, and event system better than a shared base can.

---

## Ecosystem Context

```
casehub-ledger        (audit/provenance — this project)
    ↑         ↑         ↑
 tarkus    qhorus    casehub    (each adds its own LedgerEntry subclass)
    ↑         ↑
          claudony
```

**Current consumers:**

| Consumer | Subclass | `subjectId` maps to | Added fields |
|---|---|---|---|
| `casehub-work` | `WorkItemLedgerEntry` | WorkItem UUID | `commandType`, `eventType` |
| `casehub-qhorus` | `AgentMessageLedgerEntry` | Channel UUID | `toolName`, `durationMs`, `tokenCount`, `contextRefs`, `sourceEntity` |

---

## Further Reading

| Document | What it covers |
|---|---|
| [`DESIGN-capabilities.md`](DESIGN-capabilities.md) | Merkle Mountain Range, W3C PROV-DM JSON-LD export, `@ConfigRoot` config wiring, privacy/pseudonymisation, agent identity model, agent mesh topology |

---

## Architecture

### JPA JOINED Inheritance

`LedgerEntry` is abstract with `@Inheritance(strategy = JOINED)`. The base
`ledger_entry` table holds all common audit fields. Each consumer adds a sibling
table joining on `id`.

```
ledger_entry (base — V1000)
  ├── work_item_ledger_entry     ← casehub-work (V100 in Tarkus)
  └── agent_message_ledger_entry ← casehub-qhorus (V1004+ in Qhorus)
```

`LedgerAttestation` references `ledger_entry.id` directly — attestations work
for any subclass without any changes. `ActorTrustScore` references `actorId`
from base entries — trust scoring works across all consumers.

### Base Tables (created by this extension)

| Table | Migration | Purpose |
|---|---|---|
| `ledger_entry` | V1000 | Base audit record (discriminator column: `dtype`) |
| `ledger_attestation` | V1000 | Peer verdicts — FK to `ledger_entry.id` |
| `actor_trust_score` | V1001 | Trust scores — discriminator model `(actor_id, score_type, scope_key)` |
| `ledger_supplement_compliance` | V1002 | ComplianceSupplement joined table |
| `ledger_supplement_provenance` | V1002 | ProvenanceSupplement joined table |
| `ledger_entry_archive` | V1003 | Archive records before retention deletion |
| `actor_identity` | V1004 | Actor pseudonymisation token-to-identity mapping |
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
| `ProvenanceSupplement` | `ledger_supplement_provenance` | `sourceEntityId`, `sourceEntityType`, `sourceEntitySystem`, `agentConfigHash` | Entry is driven by an external workflow system; or carries LLM agent configuration binding |

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
`casehub-ledger` require zero changes.

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
| V1000–V1003 | `casehub-ledger` base | Base schema (reserved — do not use in consumers) |
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
| `trust-score.eigentrust-enabled` | `false` | Run EigenTrust power iteration after the Beta pass to compute transitive global trust scores |
| `trust-score.eigentrust-alpha` | `0.15` | EigenTrust dampening constant α — higher values anchor the eigenvector closer to the pre-trusted set |
| `trust-score.pre-trusted-actors` | (empty) | Comma-separated actor IDs used as the EigenTrust seed; uniform distribution used when empty |
| `trust-score.schedule` | `24h` | Recomputation interval as a Quarkus duration string; reduce for high-interaction agent mesh deployments |

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
| OTel trace ID auto-wiring | ✅ Done — `TraceIdEnricher` auto-populates `traceId` from the active OTel span via the `LedgerEntryEnricher` pipeline (`LedgerTraceListener`). Closes #30, #31, #67. |
| Event replay / CQRS projections | The ledger is an append-only audit record, not a source of truth for domain state |

---

## Roadmap

### Near-term

**Trust scoring** uses a Bayesian Beta model. For each actor, all attestations across all
decisions accumulate into a Beta distribution: `α` for positive verdicts (SOUND, ENDORSED),
`β` for negative verdicts (FLAGGED, CHALLENGED). Each contribution is recency-weighted:
`weight = 2^(-ageInDays / decayHalfLifeDays)` using the attestation's own timestamp.
Prior is Beta(1,1) → score 0.5 with no history. Score = α/(α+β).

`ActorTrustScore` uses a discriminator model keyed by `(actor_id, score_type, scope_key)`.
`score_type` is `GLOBAL` (classic cross-decision Beta score), `CAPABILITY` (scoped to a
capability tag — wired by #61), or `DIMENSION` (scoped to a trust dimension — wired by #62).
`scope_key` is null for GLOBAL rows; the unique constraint uses `NULLS NOT DISTINCT` to
enforce one GLOBAL row per actor. `TrustScoreJob` writes GLOBAL rows; capability and dimension
computation is added by #61 and #62 respectively.

**EigenTrust transitivity** (opt-in, `quarkus.ledger.trust-score.eigentrust-enabled`) — runs after the Beta pass. `EigenTrustComputer` builds a peer trust matrix C from attestation data (C[i][j] = normalised positive attestations from i on j's decisions), then runs power iteration with dampening: `t = (1-α) * Cᵀ * t + α * p`. The result is each actor's eigenvector trust share accounting for transitive relationships. Pre-trusted actors (platform SYSTEM actors, or configured via `pre-trusted-actors`) seed the distribution p.

**Privacy / pseudonymisation** — ✅ Done. `ActorIdentityProvider` + `DecisionContextSanitiser` SPIs, built-in UUID tokenisation, `LedgerErasureService` for GDPR Art.17 requests. See `docs/PRIVACY.md`.

### Medium-term

**Submission target under review** — external feedback suggests `casehub-ledger` may not
qualify as a Quarkus extension under Quarkiverse criteria; SmallRye is under consideration
as an alternative. Structurally ready (192 tests, full docs, CI). Parked pending target
decision — see `IDEAS.md` (2026-04-23 entry).

**OTel trace ID auto-wiring** — ✅ Done. `LedgerTraceListener` runs the `LedgerEntryEnricher` pipeline at `@PrePersist`. `TraceIdEnricher` populates `traceId` from the active OTel span. New enrichers register by implementing `LedgerEntryEnricher` as a CDI bean — used by #59 (ProvenanceCaptureEnricher). Closed #30, #31, #67.

### Longer-term (depends on CaseHub)

**CaseLedgerEntry** — CaseHub (`/Users/mdproctor/dev/casehub-engine`) is an active project managing case lifecycle (`CaseInstance`, states: RUNNING/WAITING/SUSPENDED/COMPLETED/FAULTED/CANCELLED). It has a lightweight `EventLog` but no ledger integration yet. Pattern established; blocked on CaseHub Epic #131 (WorkBroker integration — event model still evolving). Aggregate: `CaseInstance.uuid` → `subjectId`. Tracked in #39.

**`@Alternative` activation documentation** — ✅ Done. `integration-guide.md` covers all three activation paths (`quarkus.arc.selected-alternatives`, `beans.xml`, subclass extension) with a decision table. Misleading "no extra configuration needed" note corrected.

**Trust score routing signals** — ✅ Done. `TrustScoreRoutingPublisher` fires CDI events (`TrustScoreFullPayload`, `TrustScoreDeltaPayload`) after each nightly batch run; sync/async per-consumer. Closes #33.

---

## Implementation Tracker

| Phase | Status | What |
|---|---|---|
| **Initial extraction** | ✅ Done | Abstract LedgerEntry, LedgerAttestation, ActorTrustScore, LedgerMerkleTree, TrustScoreComputer, TrustScoreJob, SPI, LedgerConfig, Flyway V1000/V1001, jandex, @Alternative, @ConfigRoot |
| **Unit tests** | ✅ Done | 42 unit tests — LedgerMerkleTree (22, LedgerMerkleTreeTest) + TrustScoreComputer (16) + LedgerSupplementSerializer (8) — extended to 159 with Merkle, publisher, PROV-DM, privacy, and IT tests |
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
| **Privacy / pseudonymisation** | ✅ Done | `ActorIdentityProvider` + `DecisionContextSanitiser` SPIs, `InternalActorIdentityProvider`, `LedgerErasureService`, `ActorIdentity` entity, V1004 migration. 31 tests. |
| **EigenTrust transitivity** | ✅ Done | `EigenTrustComputer` (power iteration, dangling-node fix, pre-trusted seed), `global_trust_score` on `ActorTrustScore`, `TrustScoreJob` eigentrust pass (opt-in). 8 unit tests. Closes #26. |
| **LLM agent identity model** | ✅ Done | Versioned persona names (`{model-family}:{persona}@{major}`); `agentConfigHash` on `ProvenanceSupplement` for config drift detection; DESIGN-capabilities.md agent identity section; ADR 0004. Closes #23. |
| **Trust score continuity across LLM sessions** | ✅ Done | Documented sparse/concurrent/scheduling behaviour in agent mesh deployments; `trust-score.schedule` config key (default `24h`, configurable for high-interaction meshes). Closes #24. |
| **Agent identity versioning criteria** | ✅ Done | Concrete bump/no-bump criteria for CLAUDE.md changes; no inheritance API (clean break is safe default); pre-seeding via synthetic attestations documented. ADR 0004 updated. Closes #25. |
| **Agent mesh topology** | ✅ Done | Centralized recommended for current ecosystem; hierarchical path documented for distributed Claudony; gossip ruled out. Closes #27. |
| **Submission target decision** | ⬜ Pending | Quarkiverse vs SmallRye — external feedback questions whether this qualifies as a Quarkus extension. See IDEAS.md 2026-04-23. |
| **OTel trace ID auto-wiring** | ✅ Done | `LedgerEntryEnricher` SPI + `LedgerTraceListener` pipeline runner; `TraceIdEnricher` populates `traceId` from active OTel span. `correlationId` renamed to `traceId`. Closes #30, #31, #67. |
| **Trust score routing signals** | ✅ Done | `TrustScoreRoutingPublisher`, payload types (`TrustScoreFullPayload`, `TrustScoreDeltaPayload`, `TrustScoreComputedAt`, `TrustScoreDelta`), `LedgerConfig.routingDeltaThreshold`, `TrustScoreJob` wiring. CDI `event.fire()` + `fireAsync()` per payload type; sync/async per-consumer. Closes #33. |
| **CaseLedgerEntry** | ⬜ Pending | Blocked on CaseHub Epic #131 (WorkBroker integration). `CaseInstance.uuid` → subjectId. Refs #39. |
