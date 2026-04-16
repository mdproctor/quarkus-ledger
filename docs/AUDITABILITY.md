# Quarkus Ledger — Auditability Self-Assessment

**Framework:** 8-axiom model from *"Creating Characteristically Auditable Agentic AI Systems"*
(ACM Intelligent Robotics FAIR 2025). Extended here to agentic-AI collaboration systems
specifically — trust between AI agents, auditability of automated decisions, and regulatory
compliance in multi-agent orchestration (Tarkus → Qhorus → Claudony).

**Purpose of this document:** Internal gap analysis to drive feature prioritisation.
Gaps are stated honestly. Where a gap is uncomfortable, that is the point — it is the
thing to fix.

**Last assessed:** 2026-04-16 against `quarkus-ledger` v1.0.0-SNAPSHOT.

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
| 3. Temporal Coherence | ⚠️ Partial | Causality field `causedBy` (#5 roadmap) |
| 4. Verifiability | ⚠️ Partial | Hash chain verification endpoint (medium-term) |
| 5. Accessibility | ⚠️ Partial | EU AI Act Article 12 audit query API (#4) |
| 6. Resource Proportionality | ⚠️ Partial | Retention config (#4), risk-tiered logging (not yet planned) |
| 7. Privacy Compatibility | ❌ Gap | Pseudonymisation strategy (not yet designed) |
| 8. Governance Alignment | ✅ Addressed | ComplianceSupplement (#7) + EU AI Act Art. 12 (#4) |

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
Strong. `LedgerHashChain` computes a SHA-256 chain per `subjectId`:

```
hash = SHA-256(subjectId | seqNum | entryType | actorId | actorRole | occurredAt | prevHash)
```

Note: `planRef` was removed from the canonical form (V1002) — it now lives in
`ComplianceSupplement`. Supplement fields are deliberately excluded from the chain.

Each entry's hash covers the previous entry's hash, so any modification to any entry
in the chain invalidates all subsequent hashes. The canonical form is deliberately
domain-agnostic — subclass fields (`commandType`, `toolName`, etc.) are excluded,
so the chain works identically for all consumers.

**Gap:**
`LedgerHashChain.verify()` exists as a pure static utility but is not exposed as a
service. An external auditor wanting to verify chain integrity must have direct database
access and write their own verification code. The chain is tamper-evident but not
independently verifiable without system internals. See Axiom 4 (Verifiability) for
the related gap.

**How to incorporate:**
No change needed to the core chain mechanism. The gap closes when a verification
endpoint is exposed (medium-term roadmap).

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

### 3. Temporal Coherence ⚠️ Partial

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

**How to incorporate (without breaking existing consumers):**
A nullable `causedBy UUID` field on `LedgerEntry` (FK to `ledger_entry.id`) — defaulting
to `null`, requiring no consumer changes unless they want to express causality. Consumers
that currently write entries without a causal link simply leave it null. This is roadmap
item #5.

---

### 4. Verifiability ⚠️ Partial

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

**Current state:**
Partial. `LedgerHashChain.verify(entries)` is a correct implementation of chain
verification, but it requires the caller to fetch the entries from the database and
invoke the method directly. There is no HTTP endpoint, no service method exposed via
CDI, and no documentation of how a third party would perform verification.

**Gap:**
Verification is an in-process operation only. An external auditor cannot verify the
chain without: direct database access, knowledge of the `LedgerEntry` schema, and
the ability to call the `verify()` method. The chain is tamper-evident internally but
not independently verifiable externally.

**How to incorporate (without breaking existing consumers):**
A `LedgerVerificationService` CDI bean (auto-activated, no consumer configuration)
exposing `verify(subjectId)` and `verifyAll()` methods. Consumers can optionally expose
this via a REST endpoint. This mirrors how `LedgerHashChain` is already structured —
pure logic, no side effects. Not yet on the roadmap; would complement the Article 12
audit query API (#4).

---

### 5. Accessibility ⚠️ Partial

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

**Gap:**
An auditor reconstructing "everything agent X did in the last 6 months across all
subjects" cannot do so from the current SPI without writing custom queries against the
database. The EU AI Act Article 12 requirement for reconstructability of AI decisions
is unmet at the API level.

**How to incorporate (without breaking existing consumers):**
Extend `LedgerEntryRepository` SPI with auditor-oriented queries: `findByActorId()`,
`findByTimeRange()`, `findByActorRole()`. These are additive — existing consumers using
only `findBySubjectId()` are unaffected. Part of the EU AI Act Article 12 compliance
surface (roadmap #4).

---

### 6. Resource Proportionality ⚠️ Partial

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

**Gap:**
No risk-tiered logging. No retention policy. EU AI Act Article 12 specifies a minimum
6-month operational log retention and 10-year conformity documentation retention —
neither is enforced or configurable today.

**How to incorporate (without breaking existing consumers):**
`quarkus.ledger.retention.operational-days=180` with a sensible default (or disabled
by default). If unconfigured, behaviour is unchanged — no retention enforcement, no
surprise deletions. Consumers that need compliance enforcement opt in via config.
Roadmap item #4.

---

### 7. Privacy Compatibility ❌ Gap

**What it means:**
The audit system respects data privacy requirements — including the right to erasure,
data minimisation, and purpose limitation — without destroying the integrity of the
audit record. Privacy and auditability are in inherent tension; the axiom requires a
design that does not sacrifice one for the other.

**Why it matters for agentic AI:**
`actorId` and decision context fields in `LedgerEntry` may contain personal data under
GDPR — especially in systems where agents act on behalf of identifiable individuals.
The GDPR right to erasure ("right to be forgotten") directly conflicts with an
append-only immutable ledger. An entry cannot be modified without breaking the hash
chain. An actor cannot be "forgotten" without invalidating their entire chain history.

**Current state:**
Gap. `actorId` and `actorRole` (core fields) plus any data stored in attached supplements
(`ComplianceSupplement.decisionContext`, `ComplianceSupplement.planRef`, etc.) are stored
permanently with no retention limit and no anonymisation mechanism. The hash chain's immutability
guarantee is structurally incompatible with erasure of individual entries. There is no
design for how these requirements would be reconciled.

**Gap:**
GDPR right-to-erasure has no implementation path with the current design. Deleting an
entry breaks the chain. Modifying `actorId` to a pseudonym breaks the chain (the hash
covers `actorId`). A consumer receiving an erasure request for an actor has no supported
way to satisfy it.

**How to incorporate (without breaking existing consumers):**
The standard approach for this problem is **pseudonymisation at write time** — the
ledger stores a pseudonym (e.g., a keyed HMAC of the real actorId), not the actorId
itself. The mapping table lives outside the ledger. Erasure = delete the mapping row;
the chain remains intact with pseudonyms. This requires a design decision on the
pseudonymisation scheme before any implementation. It also requires consumer changes
(pass pseudonym, not real actorId) — which technically violates the zero-complexity
constraint for existing consumers. This is the hardest gap to close.

**Risk without this:**
High for any deployment where `actorId` is a user identifier. Medium for pure
agent-to-agent systems where actorId is a service identity with no natural person behind
it.

**Priority:**
Not in the current sprint. Needs dedicated design work before implementation.
Flag for the next roadmap review.

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

**Current state — ✅ Addressed for GDPR Art.22 (#7), ⚠️ Partial for EU AI Act Art.12 (#9 pending)**

GDPR Art.22 structured decision fields are now delivered via `ComplianceSupplement`:
`algorithmRef`, `confidenceScore`, `contestationUri`, `humanOverrideAvailable`, and
`decisionContext`. All fields are nullable — existing consumers unaffected. See
`examples/art22-decision-snapshot/` for a runnable GDPR Art.22 compliant example.

The hash chain satisfies the tamper-evidence requirement. Supplement fields are
deliberately excluded from the canonical form.

**Remaining gap (EU AI Act Art.12):**
- No 6-month retention enforcement (`quarkus.ledger.retention.*` config not yet implemented)
- No reconstructability proof (an auditor-facing query API does not yet exist)
- No documentation mapping Art.12 requirements to specific ledger features

**How to incorporate (without breaking existing consumers):**
- **EU AI Act Art. 12:** Add `quarkus.ledger.retention.*` config (disabled by default),
  auditor-facing query API (`findByActorId`, `findByTimeRange`), and compliance
  documentation. Roadmap item #9 (open issue).

---

## Gap Closing — Feature Map

The following table maps each open gap to the roadmap item that closes it, and confirms
that closing each gap satisfies the zero-complexity constraint for existing consumers.

| Gap | Roadmap item | Zero-complexity? |
|---|---|---|
| No verification endpoint (Axiom 4) | Hash chain verification helper (medium-term) | ✅ Additive CDI bean |
| No cross-subject causality (Axiom 3) | Causality field `causedBy` (roadmap #5) | ✅ Nullable field, null by default |
| No auditor query API (Axiom 5) | EU AI Act Art. 12 compliance surface (#4) | ✅ SPI extension, additive |
| No retention policy (Axiom 6) | EU AI Act Art. 12 compliance surface (#4) | ✅ Config, disabled by default |
| No Art. 22 decision fields (Axiom 8) | GDPR Art. 22 enrichment (#1) | ✅ ComplianceSupplement — all fields nullable, zero boilerplate |
| No coverage enforcement (Axiom 2) | `@Auditable` CDI interceptor (not yet planned) | ✅ Opt-in annotation |
| Privacy / right-to-erasure (Axiom 7) | Pseudonymisation design (not yet planned) | ❌ Requires consumer changes |

---

## Reference

- Axiom framework: [Creating Characteristically Auditable Agentic AI Systems](https://dl.acm.org/doi/10.1145/3759355.3759356)
  (ACM Intelligent Robotics FAIR 2025)
- EU AI Act Article 12: [Official text](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-12/)
- GDPR Article 22: [gdpr-info.eu](https://gdpr-info.eu/art-22-gdpr/)
- Priority matrix and research sources: `docs/RESEARCH.md`
