# 0004 — LLM agent identity model: versioned persona names

Date: 2026-04-21
Status: Accepted

## Context and Problem Statement

`actorId` on `LedgerEntry` expects a stable identifier so that trust accumulates and
audit trails are coherent across sessions. LLM agents (Claude, GPT-4, etc.) are stateless
— each session starts fresh with no memory of the previous one. What should `actorId`
map to for an LLM agent so that trust scores accumulate and audit trails remain coherent
across sessions?

## Options Considered

**1. Session ID** — each session is a distinct actor. Simple, but trust never accumulates.
Rejected: defeats the purpose of a reputation system.

**2. Configuration hash** — `sha256(CLAUDE.md + memory files + system prompt)`. Precise
binding to configuration state, but memory files evolve by design — every session update
produces a new hash, effectively resetting trust each session. Rejected as primary key.

**3. Named persona** — stable label such as `"tarkus-reviewer"`. Stable and human-readable;
trust accumulates correctly. Underspecified: no mechanism to signal when behaviour has
changed materially enough to warrant a new trust baseline.

**4. Versioned persona** — `"{model-family}:{persona}@{major}"`, e.g.
`"claude:tarkus-reviewer@v1"`. Stable within a version; a deliberate version bump signals
a behaviour break and resets the trust baseline. Supported by W3C PROV-DM (stable agent
URI requirement), NIST AI Agent Standards Initiative (NCCoE concept paper, Feb 2026),
BAID framework (arxiv:2512.17538), PROV-AGENT (arxiv:2508.02866), and converging
industry practice (LangChain, AutoGen, CrewAI all moving toward stable named identities).

## Decision

**`actorId` for LLM agents is a versioned persona name.**

Format: `"{model-family}:{persona}@{major}"` — e.g. `"claude:tarkus-reviewer@v1"`.

| Segment | Description | Example |
|---|---|---|
| `model-family` | LLM family — `claude`, `gpt`, `gemini`, … | `claude` |
| `persona` | Stable role name from system instructions | `tarkus-reviewer` |
| `@{major}` | Major version; bumped on behaviour break | `@v1` |

**Versioning semantics:**

| Change type | Version impact | Trust inheritance |
|---|---|---|
| Behaviour break / major prompt rework | Bump major (`v1` → `v2`) | New baseline — no inheritance |
| Feature add / tuning within same persona | No bump | Full inheritance |
| Bug fix / internal refactor | No bump | Full inheritance |

Versioning is intentional and human-controlled. The decision criterion is:
"does this change warrant resetting the trust baseline?" If yes, bump. If no, don't.

**Configuration binding for forensics:**

`ProvenanceSupplement.agentConfigHash` (nullable `VARCHAR(64)`) carries the SHA-256 hex
of the agent's configuration state at session start (CLAUDE.md + system prompts). This
is not the trust key — it is a forensic audit field that enables configuration drift
detection within a persona version without disrupting trust accumulation.

**Session correlation:**

The existing `correlationId` field on `LedgerEntry` carries the ephemeral session or
trace identifier. It is not the actor ID.

## Consumer conventions

```java
// Tarkus — Claude acting as code reviewer
entry.actorId   = "claude:tarkus-reviewer@v1";
entry.actorType = ActorType.AGENT;
entry.actorRole = "code-reviewer";

ProvenanceSupplement ps = new ProvenanceSupplement();
ps.agentConfigHash = sha256HexOfClaudeMd;  // optional; forensic only
entry.attach(ps);

// Qhorus — Claude acting as message router
entry.actorId   = "claude:message-router@v1";
entry.actorType = ActorType.AGENT;
entry.actorRole = "router";
```

## Consequences

- **Stable trust accumulation** — trust scores accumulate per `actorId` string across
  sessions; no special handling required in the trust engine or EigenTrust computer.
- **Explicit break point** — consumers control when trust history resets by bumping the
  major version; nothing resets silently.
- **Forensic binding** — `agentConfigHash` exposes configuration drift within a version
  without affecting the trust key.
- **Consistent with W3C PROV-DM** — `actorId` maps to `prov:Agent` as a stable URI;
  the versioned string is a valid URI component per `docs/prov-dm-mapping.md`.
- **No schema changes to core tables** — `actorId` is already `VARCHAR`; the convention
  is enforced by consumer discipline, not a DB constraint.
- **One nullable column added** — `agent_config_hash VARCHAR(64)` on
  `ledger_supplement_provenance` (V1002, in-place); zero cost for consumers that omit it.

## Supporting Research

| Source | Relevance |
|---|---|
| W3C PROV-DM (https://www.w3.org/TR/prov-dm/) | Foundational provenance model; requires globally unique, dereferenceable agent URIs |
| NIST NCCoE AI Agent Identity (Feb 2026) | Apply OAuth/SPIFFE identity patterns to AI agents; inventory + lifecycle management |
| BAID: Binding Agent ID (arxiv:2512.17538) | Three-layer identity: operator binding + on-chain identity + configuration integrity |
| PROV-AGENT (arxiv:2508.02866) | Extends W3C PROV for AI workflows with MCP integration; agent identity as first-class concept |
| AI Agents with DIDs and VCs (arxiv:2511.02841) | Decentralized Identifiers for cross-domain agent trust; validates stable persistent identity requirement |
| EU AI Act Art.12 | Requires signed logs tying model output to model version and governing policy |
| ISO/IEC 42001:2023 | Unique traceable identities required for AI management systems |
