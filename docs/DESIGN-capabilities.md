# CaseHub Ledger — Capabilities Design

> Part of the casehub-ledger design documentation. See [`DESIGN.md`](DESIGN.md) for
> entity model, architecture, SPI contracts, and configuration.

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

## `@ConfigRoot` alongside `@ConfigMapping`

`LedgerConfig` carries both annotations. `@ConfigMapping` provides the SmallRye nested
interface API; `@ConfigRoot(phase = ConfigPhase.RUN_TIME)` tells the
`quarkus-extension-processor` to emit the `quarkus.ledger` prefix into the extension
descriptor. Without `@ConfigRoot`, consuming apps see "Unrecognized configuration key"
warnings and cannot override defaults via `application.properties`.

---

## Privacy and Pseudonymisation

Actor identities (`actorId`, `attestorId`) and decision context blobs are intercepted on
every write by two SPIs in `io.casehub.ledger.runtime.privacy`:

| SPI | Default | Purpose |
|---|---|---|
| `ActorIdentityProvider` | Pass-through | Tokenise actor identities; resolve tokens back to real identities; sever mappings on erasure |
| `DecisionContextSanitiser` | Pass-through | Strip PII from `ComplianceSupplement.decisionContext` before persist |

Both defaults produce zero behaviour change. Supply a custom CDI bean to replace either.

**Built-in tokenisation** (`InternalActorIdentityProvider`) activates when
`quarkus.ledger.identity.tokenisation.enabled=true`. Tokens are UUID strings stored in the
`actor_identity` table (V1004). Erasure deletes the mapping row — the token in existing
entries becomes permanently unresolvable but the Merkle hash chain is intact.

**`LedgerErasureService`** processes GDPR Art.17 erasure requests. Returns `ErasureResult`
with the actor identity, whether a mapping was found, and how many ledger entries were
affected (entries are not deleted).

**Config:**

| Key | Default | Description |
|---|---|---|
| `quarkus.ledger.identity.tokenisation.enabled` | `false` | Activate built-in UUID token pseudonymisation (see `docs/PRIVACY.md`) |

---

## Agent Identity Model

LLM agents are stateless — each session starts fresh. For trust scores to accumulate and
audit trails to remain coherent, `actorId` must be stable across sessions. See ADR 0004.

### `actorId` format for LLM agents

```
{model-family}:{persona}@{major}
```

Examples: `"claude:tarkus-reviewer@v1"`, `"claude:message-router@v1"`.

| Segment | Description |
|---|---|
| `model-family` | LLM family: `claude`, `gpt`, `gemini`, … |
| `persona` | Stable role name from the agent's system instructions |
| `@{major}` | Major version; bumped when behaviour changes enough to warrant a new trust baseline |

### Versioning semantics

| Change type | Version impact | Trust |
|---|---|---|
| Behaviour break / major system instruction rework | Bump major (`v1` → `v2`) | New baseline |
| Feature add or tuning within same persona | No bump | Full inheritance |
| Bug fix or internal refactor | No bump | Full inheritance |

Versioning is intentional and human-controlled — it is not automatic. The question is:
"does this change warrant resetting the trust baseline?" If yes, bump. If no, don't.

**Concrete criteria for CLAUDE.md / system instruction changes:**

| Change | Bump? |
|---|---|
| Complete role redefinition | Yes |
| New decision authority (e.g. now approves financial actions) | Yes |
| Significant tightening or loosening of behavioural constraints | Yes |
| Prompt tuning, worked examples, clarifications | No |
| Memory file updates (accumulated knowledge) | No |
| Bug fix in instructions | No |
| Model family upgrade with same instructions | Consumer discretion |

**Score inheritance:** there is no inheritance API. When a consumer bumps from `@v1` to
`@v2`, v2 starts at Beta(1,1) = 0.5 (prior). A clean break is the safe default — the
new configuration earns trust independently. Consumers who want to pre-seed v2 trust
can write synthetic attestations before go-live, which leaves an explicit auditable trail.
See ADR 0004 for the full rationale.

### Three-layer model

| Layer | Field | Description |
|---|---|---|
| Persistent identity | `actorId` | Stable trust key — `"{model-family}:{persona}@{major}"` |
| Configuration binding | `ProvenanceSupplement.agentConfigHash` | SHA-256 hex of CLAUDE.md + system prompts; forensic only; nullable |
| Session correlation | `correlationId` | Ephemeral session/trace ID; not the actor ID |

### Consumer conventions

```java
entry.actorId   = "claude:tarkus-reviewer@v1";
entry.actorType = ActorType.AGENT;
entry.actorRole = "code-reviewer";  // broader functional classification

// Optional — populate for forensic config drift detection
ProvenanceSupplement ps = new ProvenanceSupplement();
ps.agentConfigHash = sha256HexOf(claudeMd + systemPrompts);
entry.attach(ps);
```

The `actorId` string is also valid as a `prov:Agent` URI in W3C PROV-DM exports
(`ledger:actor/claude:tarkus-reviewer@v1`). See `docs/prov-dm-mapping.md`.

### Trust score behaviour in agent mesh deployments

**Sparse sessions (1 decision, 1 attestation):** The Beta model handles this via the
prior. Beta(1,1) = 0.5 with no history; one positive attestation yields Beta(2,1) = 0.67.
The contribution is real but carries low confidence weight — exactly the right behaviour.
No special handling is needed for short-lived sessions.

**Concurrent sessions:** Multiple sessions with the same `actorId` running in parallel
produce concurrent appends to `ledger_attestation` — plain inserts with no conflict.
`TrustScoreJob` is a single serialized batch (Quarkus prevents concurrent executions of
the same job identity). Attestations written after a batch starts are picked up on the
next run. This is expected batch semantics, not a data hazard.

**Scheduling for high-interaction meshes:** The default `trust-score.schedule=24h` is
appropriate for most deployments. For dense agent meshes where an actor makes hundreds of
decisions per hour, reduce the interval so trust scores reflect recent behaviour:

```properties
quarkus.ledger.trust-score.schedule=1h   # high-interaction mesh
quarkus.ledger.trust-score.schedule=6h   # moderate interaction
quarkus.ledger.trust-score.schedule=24h  # default (nightly)
```

There is no benefit to scheduling below the typical inter-attestation interval — scores
cannot change faster than attestations arrive.

---

## Agent Mesh Topology

Three topologies are possible for deploying `casehub-ledger` across a mesh of LLM agents.

| Topology | Description | When appropriate |
|---|---|---|
| **Centralized** (current) | All agents write to one ledger instance | ✅ Correct for current ecosystem |
| **Hierarchical** | Each node has a local ledger; a root orchestrator aggregates | When Claudony itself becomes distributed |
| **Gossip-based** | Agents exchange attestations peer-to-peer and converge on a shared view | Only if adversarial agents are a real threat; significant complexity cost |

### Recommendation: centralized

For the current Tarkus / Qhorus / Claudony ecosystem, **centralized is correct**.
Claudony is the natural orchestrator and the natural ledger owner — all agent decisions
flow through it, so a single ledger gives complete visibility with no synchronisation
complexity. The Merkle Mountain Range provides tamper evidence; the EigenTrust pass
provides transitive reputation — both work best with a full global view of attestations.

### When hierarchical becomes relevant

If Claudony itself is distributed (multiple Claudony instances coordinating across
datacentres), a hierarchical topology becomes appropriate:

- Each Claudony instance maintains a local ledger for its agents.
- A root aggregator periodically merges frontier hashes and runs the EigenTrust pass
  over the combined attestation graph.
- Merkle roots from each shard can be cross-attested for tamper evidence.

No extension changes are required to support this — the current `LedgerEntry` model and
trust engine are topology-agnostic. The aggregation layer is a consumer responsibility.

### Why gossip is out of scope

Gossip-based convergence requires conflict resolution, eventual consistency guarantees,
and Byzantine-fault-tolerant attestation handling. This complexity is only warranted when
agents are genuinely adversarial and cannot trust the orchestrator. That is not the threat
model for the current ecosystem. If it becomes one, the right answer is a purpose-built
consensus layer, not an extension to `casehub-ledger`.

---

