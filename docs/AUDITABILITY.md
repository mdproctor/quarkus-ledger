# CaseHub Ledger — Auditability Self-Assessment

**Framework:** 8-axiom model from *"Creating Characteristically Auditable Agentic AI Systems"*
(ACM Intelligent Robotics FAIR 2025). Extended here to agentic-AI collaboration systems
specifically — trust between AI agents, auditability of automated decisions, and regulatory
compliance in multi-agent orchestration (Tarkus → Qhorus → Claudony).

**Purpose of this document:** Internal gap analysis to drive feature prioritisation.
Gaps are stated honestly. Where a gap is uncomfortable, that is the point — it is the
thing to fix.

**Last assessed:** 2026-04-21 against `casehub-ledger` v0.2-SNAPSHOT.

---

## Design Constraint — Zero Complexity for Existing Use Cases

Every gap resolution in this document must satisfy one test before it is considered viable:

> **If a consumer does not use the new capability, it must not be required to change
> anything — no new config keys, no new beans to declare, no new boilerplate.**

This follows Quarkus's own philosophy: CDI `@Alternative` beans, config with sensible
defaults, opt-in via annotation. Features that violate this constraint are out of scope
regardless of their value. The complexity cost of a shared base extension is borne by
every consumer; it must never surprise them.

---

## Axiom Summary

| Axiom | Status | Addressed by |
|---|---|---|
| 1. Integrity | ✅ Strong | — (already met) |
| 2. Coverage | ⚠️ Partial | CDI interceptors (not yet planned) |
| 3. Temporal Coherence | ✅ Addressed | `causedByEntryId` core field + `findCausedBy()` (#10) |
| 4. Verifiability | ✅ Addressed | Merkle tree upgrade (#11) — O(log N) inclusion proofs + Ed25519 publishing |
| 5. Accessibility | ✅ Addressed | EU AI Act Art.12 audit query API (#9) |
| 6. Resource Proportionality | ✅ Addressed | Retention config (#9) |
| 7. Privacy Compatibility | ✅ Addressed | `ActorIdentityProvider` + `DecisionContextSanitiser` SPIs. Built-in tokenisation via `quarkus.ledger.identity.tokenisation.enabled`. `LedgerErasureService` for GDPR Art.17 requests. |
| 8. Governance Alignment | ✅ Addressed | ComplianceSupplement (#7) + EU AI Act Art. 12 (#9) |

---

## Axiom Assessments

---

### 1. Integrity ✅ Strong

**What it means:**
Audit records cannot be tampered with or retroactively altered without detection.
Integrity is the foundation — without it, every other axiom is meaningless because
an adversary could simply rewrite the log.

**Why it matters for agentic AI:**
AI agents may produce decisions with legal or financial consequences. If an agent
(or its operator) could silently modify its own audit trail after the fact, accountability
collapses. A trust system built on mutable logs is not a trust system.

**Current state:**
Strong. Each entry carries an RFC 9162 Merkle leaf hash (`digest`):

```
leaf = SHA-256(0x00 | subjectId | seqNum | entryType | actorId | actorRole | occurredAt)
```

Entries accumulate into a Merkle Mountain Range (stored frontier). Any modification
to any entry changes its leaf hash and invalidates the tree root. The canonical form is
deliberately domain-agnostic — subclass fields (`commandType`, `toolName`, etc.) and
supplement fields are excluded, so the chain works identically for all consumers.

`LedgerVerificationService.verify(subjectId)` recomputes the full MMR from stored entries
and compares against the stored frontier. `inclusionProof(entryId)` produces a compact
O(log N) proof verifiable without database access.

**Gap (closed):**
`LedgerVerificationService` provides `verify(subjectId)`, `treeRoot(subjectId)`, and
`inclusionProof(entryId)` as a CDI bean — no database credentials or schema knowledge
needed by an external verifier. See Axiom 4 (Verifiability) for the full treatment.

---

### 2. Coverage ⚠️ Partial

**What it means:**
All relevant agent actions are captured in the audit record — not just the ones the
developer remembered to log. Coverage is incomplete if there are actions that *should*
produce ledger entries but don't because a capture call was omitted.

**Why it matters for agentic AI:**
In multi-agent systems, an unlogged action is an invisible action. If Qhorus routes a
message without capturing a ledger entry, that decision is unauditable. EU AI Act
Article 12 requires automatic event recording — "automatic" implies the system captures
events, not that developers manually instrument every code path.

**Current state:**
Partial. The ledger captures exactly what consumers explicitly write to it via their
own capture services. There is no automatic capture mechanism in the base extension.
If a consumer forgets to write a ledger entry for an action, it is silently absent from
the audit trail. Coverage completeness is unverifiable from the ledger itself.

**Gap:**
Coverage is entirely opt-in per action. There is no way to declare "this method must
always produce a ledger entry" and have the framework enforce it. A developer adding a
new code path in Tarkus or Qhorus must remember to add the capture call — there is no
safety net.

**How to incorporate (without breaking existing consumers):**
A `@Auditable` method-level annotation processed by a CDI interceptor could auto-capture
ledger entries for annotated methods without any changes to existing consumers. Existing
consumers already writing their own capture calls would simply not use the annotation —
zero impact. This is not yet designed or planned.

**Risk without this:**
High. As the ecosystem grows (CaseHub, Claudony), the probability of a missing capture
call increases with the number of developers and code paths. Coverage gaps are silent
and discoverable only after an audit question cannot be answered.

---

### 3. Temporal Coherence ✅ Addressed (#10)

**What it means:**
Events in the audit log are ordered consistently and reflect the actual order in which
things happened. No two entries are ambiguously ordered. Causally related entries are
identifiably linked.

**Why it matters for agentic AI:**
When an orchestrator (Claudony) triggers a task in Tarkus, which triggers an agent
message in Qhorus, the three resulting ledger entries are causally related. If they
cannot be ordered — or worse, if their ordering appears to contradict the actual
execution sequence — reconstructing what happened becomes unreliable. This is the
difference between an audit trail and a pile of timestamps.

**Current state:**
Partial. Within a single `subjectId`, coherence is strong: `seqNum` is monotonically
increasing, and `occurredAt` is explicit. Any two entries for the same subject can be
ordered unambiguously. This is solid for single-system auditing.

Across subjects or systems, coherence is weak. There is no `causedBy` field linking
a Qhorus entry to the Tarkus entry that triggered it. Wall-clock synchronisation across
distributed nodes is not guaranteed — two entries with the same `occurredAt` millisecond
from different systems have no canonical ordering.

**Gap:**
No cross-subject causality. A full-system audit (Claudony asking "show me everything
that happened as a result of this orchestration step") cannot be answered from the
ledger alone. The causal chain exists in the application but is invisible to the
audit trail.

**Addressed by (#10):**
`causedByEntryId` is a core nullable field on `LedgerEntry` — consumers set it
directly when an entry is causally triggered by another. `traceId` is also
core, linking entries to OTel distributed traces. `findCausedBy(UUID)` enables
one-hop traversal; recursive chain reconstruction is application-level.

---

### 4. Verifiability ✅ Addressed (#11)

**What it means:**
Any party — including one without access to the producing system's internals — can
independently verify that the audit record is complete and unmodified. Verifiability
extends Integrity: it is not enough that tampering is detectable in theory; it must be
detectable in practice by someone who does not trust the system operator.

**Why it matters for agentic AI:**
Regulators, auditors, and counterparties do not have access to your database. If
verifying the integrity of an AI system's audit trail requires you to hand over database
credentials, the verification is not independent. For GDPR conformity assessments and
EU AI Act compliance, the audit trail must be provably intact without trusting the
party being audited.

**Previous state:**
The original hash chain required callers to fetch entries and call a static utility
directly — no CDI service, no documented third-party verification path.

**Status:** ✅ Addressed (#11)

**Addressed by (#11):**
- `LedgerMerkleTree` — RFC 9162 Merkle Mountain Range. Leaf hash: `SHA-256(0x00 | canonicalFields)`.
  Internal node: `SHA-256(0x01 | left | right)`. Stored frontier: ≤ log₂(N) rows per subject.
- `LedgerVerificationService` — CDI bean. `treeRoot(subjectId)`, `inclusionProof(entryId)`,
  `verify(subjectId)`. Auto-activated; no consumer configuration required.
- `LedgerMerklePublisher` — opt-in Ed25519-signed tlog-checkpoint publishing.
  Configure `quarkus.ledger.merkle.publish.url` to activate. Disabled by default.
- An external auditor needs only: a published checkpoint + an `InclusionProof` record.
  No DB access, no schema knowledge, no trust in the operator required.

---

### 5. Accessibility ✅ Addressed (#9)

**What it means:**
Auditors can retrieve and interpret audit records without needing to understand the
producing system's internal data model, query language, or code. An auditor should be
able to ask "show me everything actor X did between dates Y and Z" and receive an answer
without consulting the engineering team.

**Why it matters for agentic AI:**
EU AI Act Article 12 requires that records be retrievable and reconstructible on demand
— not just stored. A regulator conducting a conformity assessment does not want to write
SQL queries against your JPA schema. Multi-agent systems also generate entries across
multiple subjects, actorIds, and systems that an auditor may need to correlate.

**Current state:**
Partial. `LedgerEntryRepository` exposes `findBySubjectId()`. Entries are queryable by
the aggregate they belong to, which is the primary use case for consumers displaying
their own domain history. The SPI is consumer-oriented, not auditor-oriented.

There is no query by `actorId` across subjects, no time-range query, no query by
`actorRole`, and no API that presents a coherent audit view without knowing which
`subjectId` to look for.

**Status:** ✅ Addressed (#9)

**Addressed by (#9):**
`findByActorId()`, `findByActorRole()`, and `findByTimeRange()` now provide auditor-facing
reconstructability without knowledge of system internals. All return entries in
`occurredAt ASC` order using `Instant` params for timezone-safe querying.

---

### 6. Resource Proportionality ✅ Addressed (#9)

**What it means:**
The cost of auditing — storage, compute, network — is proportional to the risk level
of the system being audited. High-risk systems warrant detailed, long-retained records.
Lower-risk operational events can be captured more lightly. Over-auditing is wasteful;
under-auditing is dangerous.

**Why it matters for agentic AI:**
EU AI Act Annex III classifies specific AI use cases as "high-risk" requiring more
rigorous record-keeping than general-purpose AI. A ledger that stores all entries
identically — regardless of risk — either over-provisions for routine events or
under-provisions for high-risk ones. In high-throughput systems (Qhorus processing many
agent messages per second), undifferentiated logging becomes a performance and storage
concern.

**Current state:**
Partial. The nightly `TrustScoreJob` runs on a configurable schedule — appropriate
for its batch nature. SHA-256 hash computation adds minimal per-write overhead.
`quarkus.ledger.trust-score.*` config exists. However, all ledger entries are stored
identically regardless of their risk level. There is no retention configuration, no
risk tiering, and no way to tell the ledger that certain entry types warrant extended
retention or additional fidelity.

**Status:** ✅ Addressed (#9)

**Addressed by (#9):**
`quarkus.ledger.retention.*` config (disabled by default) enforces the EU AI Act Art.12
180-day minimum with archive-before-delete. Zero behaviour change when unconfigured.

---

### 7. Privacy Compatibility ✅ Addressed

**What it means:**
Audit records must coexist with privacy rights — including the GDPR right to erasure (Art.17).
An immutable ledger cannot delete entries without breaking tamper evidence. The solution is
pseudonymisation: store tokens instead of raw identities, backed by a detachable mapping.

**Current state:**
Two SPIs in `io.casehub.ledger.runtime.privacy`:

- `ActorIdentityProvider` — tokenises `actorId` (write) and `attestorId` on every persist.
  `tokenise()` creates a UUID token if none exists. `tokeniseForQuery()` does a read-only
  lookup. `erase()` deletes the mapping — the token in existing entries becomes unresolvable.
- `DecisionContextSanitiser` — sanitises `ComplianceSupplement.decisionContext` JSON before
  persist. Default is pass-through; consumers supply a custom CDI bean to strip PII.

Built-in implementation (`InternalActorIdentityProvider`) backed by the `actor_identity`
table (V1004). Activated via `quarkus.ledger.identity.tokenisation.enabled=true`.

`LedgerErasureService.erase(String rawActorId)` processes GDPR Art.17 requests: locates
the token, counts affected entries (informational), severs the mapping, returns `ErasureResult`.

Organisations with external identity management supply a custom `ActorIdentityProvider` CDI
bean — it replaces the default via `@DefaultBean` semantics, no config changes needed.

**Remaining gap:**
`decisionContext` PII scrubbing is the consumer's responsibility — the `DecisionContextSanitiser`
SPI provides the hook, but the extension cannot validate JSON content. `subjectId` (the
aggregate UUID) is typically not personal data; consumers who use PII as their `subjectId`
must address this outside the extension.

---

### 8. Governance Alignment ✅ Addressed (#7)

**What it means:**
The audit system is explicitly aligned with the relevant regulatory frameworks —
not just compatible in principle, but documented and implemented in a way that satisfies
specific legal requirements. Governance alignment is where the other seven axioms
become legally meaningful.

**Addressed:** `ComplianceSupplement` (delivered in #7) provides structured optional fields
for GDPR Art.22 explainability — `algorithmRef`, `confidenceScore`, `contestationUri`,
`humanOverrideAvailable`, and `decisionContext`. All fields are nullable; consumers that
do not populate them incur zero overhead.

**Why it matters for agentic AI:**
EU AI Act Article 12 (record-keeping) and GDPR Article 22 (automated decision-making)
are the two directly applicable frameworks for this ecosystem. The enforcement date
for EU AI Act high-risk AI requirements is **2 August 2026**. Penalties reach
€15M or 3% of global turnover. A ledger that is "basically compliant" but cannot
produce the specific artefacts a regulator asks for provides weak protection.

**Current state — ✅ Addressed for GDPR Art.22 (#7), ✅ Addressed for EU AI Act Art.12 (#9)**

GDPR Art.22 structured decision fields are now delivered via `ComplianceSupplement`:
`algorithmRef`, `confidenceScore`, `contestationUri`, `humanOverrideAvailable`, and
`decisionContext`. All fields are nullable — existing consumers unaffected. See
`examples/art22-decision-snapshot/` for a runnable GDPR Art.22 compliant example.

The hash chain satisfies the tamper-evidence requirement. Supplement fields are
deliberately excluded from the canonical form.

**EU AI Act Art.12 (#9) — ✅ Complete:**
- ✅ 6-month retention enforcement via `quarkus.ledger.retention.*` config (disabled by default)
- ✅ Reconstructability API: `findByActorId()`, `findByActorRole()`, `findByTimeRange()`
- ✅ Documentation mapping Art.12 requirements: `docs/compliance/EU-AI-ACT-ART12.md`
- ✅ Runnable example: `examples/art12-compliance/`

---

## Gap Closing — Feature Map

The following table maps each open gap to the roadmap item that closes it, and confirms
that closing each gap satisfies the zero-complexity constraint for existing consumers.

| Gap | Roadmap item | Zero-complexity? |
|---|---|---|
| No verification endpoint (Axiom 4) | Merkle tree upgrade (#11) | ✅ LedgerVerificationService CDI bean, auto-activated |
| No cross-subject causality (Axiom 3) | Causality field `causedBy` (roadmap #5) | ✅ Nullable field, null by default |
| No auditor query API (Axiom 5) | EU AI Act Art.12 compliance surface (#9) | ✅ findByActorId/Role/TimeRange — Instant params, ASC order |
| No retention policy (Axiom 6) | EU AI Act Art.12 compliance surface (#9) | ✅ retention.* config, disabled by default |
| No Art. 22 decision fields (Axiom 8) | GDPR Art. 22 enrichment (#1) | ✅ ComplianceSupplement — all fields nullable, zero boilerplate |
| No coverage enforcement (Axiom 2) | `@Auditable` CDI interceptor (not yet planned) | ✅ Opt-in annotation |
| Privacy / right-to-erasure (Axiom 7) | Pseudonymisation (#29) | ✅ Pass-through defaults — zero consumer changes; opt-in tokenisation via config |

---

## Reference

- Axiom framework: [Creating Characteristically Auditable Agentic AI Systems](https://dl.acm.org/doi/10.1145/3759355.3759356)
  (ACM Intelligent Robotics FAIR 2025)
- EU AI Act Article 12: [Official text](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-12/)
- GDPR Article 22: [gdpr-info.eu](https://gdpr-info.eu/art-22-gdpr/)
- Priority matrix and research sources: `docs/RESEARCH.md`
