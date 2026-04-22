# Quarkus Ledger — Capabilities Guide

This document explains what each capability does, why it exists, and when an enterprise
would enable it. The audience is the architect or technical decision-maker evaluating
`quarkus-ledger` — not as a how-to guide (see [integration-guide.md](integration-guide.md)),
but as a map of the design space and its real-world relevance.

The capabilities are grouped by concern. Each has an applicability rating:

| Rating | Meaning |
|---|---|
| ★★★★★ | Universally demanded — missing it is a compliance gap or architectural risk |
| ★★★★☆ | High demand in most enterprise contexts, especially regulated industries |
| ★★★☆☆ | Solid real-world value, context-dependent — understand the problem before enabling |
| ★★☆☆☆ | Forward-looking — solves a real problem that is not yet mainstream |
| ★☆☆☆☆ | Research-grade — valuable for specialised scenarios, not a near-term enterprise driver |

---

## Quick Reference

| Capability | Rating | Enabled by default | Key driver |
|---|---|---|---|
| Immutable append-only log | ★★★★★ | Always on | Audit, compliance, forensics |
| SHA-256 hash chain | ★★★★☆ | `true` | Basic tamper evidence |
| Merkle Mountain Range | ★★★☆☆ | `true` | Cryptographic log integrity proofs |
| Decision context snapshots | ★★★★★ | Supplement — explicit attach | GDPR Art.22 / EU AI Act Art.12 |
| Privacy / pseudonymisation | ★★★★★ | `false` | GDPR Art.17 right to erasure |
| Provenance tracking | ★★★★★ | Supplement — explicit attach | Data lineage, MLOps, governance |
| Peer attestation | ★★★★☆ | `true` | Multi-party verification, AI agent trust |
| Bayesian Beta trust scoring | ★★★☆☆ | `false` | Actor reliability confidence |
| EigenTrust transitivity | ★★☆☆☆ | `false` | Decentralised agent mesh trust |
| Trust score routing signals | ★★☆☆☆ | `false` | CDI event dispatch to routing layers |
| Supplement system | ★★★★★ | Always available | Zero-overhead optional field sets |

---

## Core Audit Foundation

### Immutable Append-Only Log ★★★★★

Every domain transition is recorded as a `LedgerEntry` row. Entries are appended, never
updated or deleted. Sequence numbers are monotonically increasing per aggregate
(`subjectId`). The record cannot be silently modified after the fact.

**Why enterprises need this:**
Regulatory audit requirements across virtually every regulated industry demand tamper-evident
records: financial services (SOX, MiFID II, FCA), healthcare (HIPAA, MDR), government
(NIST 800-53, FedRAMP), and the EU AI Act. The immutable log is the foundation everything
else builds on.

**Who benefits most:**
Every consumer. There is no scenario where you add `quarkus-ledger` and don't use this.

**Enable when:** Always — this is the core.

---

### SHA-256 Hash Chain (Merkle leaf hash) ★★★★☆

Each entry carries a `digest` — a SHA-256 leaf hash (RFC 9162 domain-separated) over its
canonical content. Entries accumulate into a Merkle Mountain Range; any tampering changes
the leaf hash and invalidates the stored tree root. Verification is offline: no database
access required, no trusted third party.

**Why enterprises need this:**
A database row that says "this happened" is deniable — an administrator with write access
could silently change it. A hash chain means any modification is detectable by anyone with
a copy of the entries. This satisfies the "integrity" requirement in frameworks like ISO
27001, SOC 2 Type II, and PCI DSS.

**Who benefits most:**
Any application where the audit log itself could be a target — financial services, legal
evidence chains, regulated healthcare systems, government contracting.

**Enable when:** Always, unless you are prototyping and want to skip the computational cost.
The performance impact is negligible (one SHA-256 per write).

**Skip when:** Pure development / exploratory environments. Set
`quarkus.ledger.hash-chain.enabled=false`.

---

### Merkle Mountain Range Tamper Evidence ★★★☆☆

The hash chain proves sequential integrity within a subject's entries. The Merkle Mountain
Range (MMR) goes further: it produces a cryptographic accumulator over all entries, enabling
**inclusion proofs** — compact cryptographic evidence that a specific entry exists in the
log at a specific position, without replaying the entire chain.

Ed25519-signed checkpoints can be published to a transparency log (compatible with
[RFC 9162](https://www.rfc-editor.org/rfc/rfc9162) — the same standard used by Google
Certificate Transparency).

**Why enterprises need this:**
The hash chain proves the log hasn't been tampered with *internally*. The MMR proves it to
*an external party* — a regulator, an auditor, a counterparty — without revealing the full
log. This is the difference between "trust us, we didn't change it" and "here is
mathematical proof."

Real-world precedent: Google's Certificate Transparency (used for TLS certificate auditing
globally), IETF SCITT (Supply Chain Integrity, Transparency and Trust), and US NIST's
post-quantum transparency initiatives all use this pattern.

**Who benefits most:**
Financial institutions subject to external audit, legal evidence preservation, government
procurement chains, any scenario where the log owner and the auditor are different parties
with adversarial interests.

**Enable when:** You need to prove log integrity to parties outside your organisation, or
when your threat model includes a compromised database administrator.

**Skip when:** The log is only ever verified internally. The hash chain is sufficient for
internal integrity; the MMR adds the external proof capability.

---

## Compliance and Privacy

### Decision Context Snapshots — GDPR Art.22 / EU AI Act Art.12 ★★★★★

The `ComplianceSupplement` captures, at the moment of an automated decision, a snapshot of
the inputs and reasoning that produced it: the algorithm reference, the confidence score,
the decision context (arbitrary JSON), a plan reference, a rationale, and whether a human
override was available. This snapshot is immutable — it records what the system *actually
used*, not what it should have used or what it uses today.

**Why enterprises need this:**
Two converging legal requirements make this mandatory for AI-assisted decisions affecting
individuals:

- **GDPR Article 22** — Individuals have the right to explanation for decisions made
  "solely on the basis of automated processing." The controller must be able to demonstrate
  what logic was applied. Without a decision snapshot, the audit record is incomplete.

- **EU AI Act Article 12** — High-risk AI systems (hiring, credit scoring, benefits
  eligibility, law enforcement, critical infrastructure) must maintain logs "to the extent
  possible" of inputs and outputs for each use of the system. Art.12 logs must be retained
  for a period defined by national law — typically five to ten years.

US state AI legislation (Colorado, California, Connecticut) is following the same pattern.
Any enterprise building AI-assisted decisions for EU or US regulated markets will face this
requirement.

**Who benefits most:**
Any system making automated or AI-assisted decisions about individuals: credit scoring,
insurance underwriting, hiring and promotion, loan origination, content moderation, benefits
eligibility, medical triage, risk assessment.

**Enable when:** Any automated or AI-assisted decision is recorded that could affect an
individual's rights or opportunities. Attach `ComplianceSupplement` explicitly at the
write site.

**See also:** [docs/compliance/EU-AI-ACT-ART12.md](compliance/EU-AI-ACT-ART12.md)

---

### Privacy / Pseudonymisation — GDPR Art.17 ★★★★★

By default, `actorId` and `attestorId` store raw identity strings. When
`quarkus.ledger.identity.tokenisation.enabled=true`, a pseudonymisation layer intercepts
all writes: raw identities are replaced with opaque UUID tokens, and the mapping is stored
in a separate `actor_identity` table. Queries translate transparently.

The right to erasure (GDPR Art.17) is served by the `LedgerErasureService`: severing the
token mapping makes the raw identity unrecoverable from the log, without deleting the
immutable audit record itself.

A `DecisionContextSanitiser` SPI allows consumers to strip or redact PII from
`decisionContext` JSON before it reaches the database — because an immutable log that
stores raw PII is a privacy liability, not just an asset.

**Why enterprises need this:**
An immutable log is, by design, permanent. GDPR's right to erasure requires that personal
data be deleted or rendered unrecoverable on request. These requirements are structurally
in tension. Pseudonymisation resolves the tension: the log record survives; the link to a
real person is severed. This is the approach recommended by EU data protection authorities
(EDPB Guidelines 05/2019).

**Who benefits most:**
Every EU-facing application that records human actors. Any application subject to CCPA, UK
GDPR, or equivalent legislation. Particularly critical for healthcare, financial services,
HR systems, and consumer-facing AI.

**Enable when:** You record any human actor identity and operate under GDPR or equivalent
legislation.

**Skip when:** All actors are systems or agents with no natural person behind them (e.g.
a machine-to-machine integration ledger with no human identity involved).

**Implement `DecisionContextSanitiser`** when your `decisionContext` JSON may contain
names, email addresses, or other personal identifiers.

---

## Provenance

### Provenance Tracking — W3C PROV-DM ★★★★★

The `ProvenanceSupplement` records where a ledger entry originated: `sourceEntityId`,
`sourceEntityType`, and `sourceEntitySystem`. This creates a data lineage trail — you can
trace any record back through the system or workflow that produced it.

The model is aligned with [W3C PROV-DM](https://www.w3.org/TR/prov-dm/) and
[docs/prov-dm-mapping.md](prov-dm-mapping.md) documents the mapping in detail.

**Why enterprises need this:**
Data governance requires knowing where data came from. MLOps requires tracing model
predictions back to training data and pipeline versions. Regulatory frameworks (BCBS 239 in
banking, FDA 21 CFR Part 11 in pharma, GDPR data minimisation) require organisations to
demonstrate they know what data they hold and where it came from. Supply chain integrity
(SBOM for software, ingredient traceability for food and pharma) is the same concept
applied to physical goods.

As AI systems produce decisions that feed downstream AI systems, provenance becomes the
mechanism for answering "did a biased upstream model contaminate this output?"

**Who benefits most:**
MLOps pipelines, data warehouses with regulatory lineage requirements, financial risk
systems, pharmaceutical batch records, AI agent orchestration where one agent's output
feeds another's input.

**Enable when:** A ledger entry is produced in response to a record in another system, or
is driven by an upstream workflow. Attach `ProvenanceSupplement` explicitly at the
write site.

---

## Trust and Reputation

### Peer Attestation ★★★★☆

Any actor can stamp a verdict on any ledger entry via `LedgerAttestation`:
`SOUND` (passes review), `FLAGGED` (warrants investigation), `ENDORSED` (actively
vouched for), or `CHALLENGED` (formally disputed). Attestations carry a confidence score
and free-text evidence.

**Why enterprises need this:**
Multi-party verification is a fundamental pattern in regulated industries:

- **Financial services:** Four-eyes principle, dual-control authorisation, trade confirmation
- **Healthcare:** Second-opinion workflows, diagnostic peer review, medication verification
- **Legal:** Countersignatures, witness attestation, discovery review
- **AI agent systems:** One agent attesting to another's output — the foundational trust
  primitive for multi-agent coordination

As AI agents proliferate, the question "which agent's output should I trust?" requires a
mechanism for agents to formally endorse or challenge each other's work. Peer attestation
is that mechanism.

**Who benefits most:**
Any multi-actor workflow where no single actor's word is final. Especially relevant for
AI agent meshes, regulated financial operations, and clinical decision systems.

**Enable when:** Multiple actors — human or agent — review, approve, or dispute each other's
decisions. `quarkus.ledger.attestations.enabled=true` (default).

---

### Bayesian Beta Trust Scoring ★★★☆☆

A nightly scheduled job (`TrustScoreJob`) recomputes a reliability score `[0.0, 1.0]` per
actor from their full attestation history. The algorithm uses Bayesian Beta distribution
accumulation: each `SOUND` or `ENDORSED` attestation increments `α`; each `FLAGGED` or
`CHALLENGED` increments `β`. Score = `α / (α + β)`. Recency weighting applies exponential
decay (configurable half-life, default 90 days) so recent behaviour dominates.

Starting prior: Beta(1,1) — an actor with no history is assumed neutral, not trusted and
not untrusted.

**Why enterprises need this:**
Not all actors are equally reliable. A trust score derived from accumulated evidence is more
robust than a binary approved/blocked state. This pattern is used at scale in fraud
detection (transaction risk models), recommendation systems (Bayesian rating systems), and
content moderation (confidence-weighted takedown decisions).

For AI agent systems specifically, trust scores enable **dynamic routing** — high-confidence
agents handle sensitive cases; low-confidence agents are flagged for human review. This is a
meaningful safety lever as agent autonomy increases.

**Who benefits most:**
AI agent orchestration platforms, fraud detection systems, content moderation pipelines,
any multi-actor workflow where actor reliability varies and routing decisions should reflect
historical performance.

**Enable when:** You have peer attestation enabled and want to derive a longitudinal
reliability signal from attestation history.
`quarkus.ledger.trust-score.enabled=true` (disabled by default — requires the attestation
dataset to be meaningful before scores are useful).

**Skip when:** You have a small, fixed, well-known actor set where manual trust management
is sufficient. Bayesian scoring adds value at scale, not for three known actors.

---

### EigenTrust Transitivity ★★☆☆☆

EigenTrust (Kamvar et al., Stanford 2003) extends peer trust scoring from direct
attestations to **transitive trust propagation**: if actor A trusts B, and B trusts C, then
A has an indirect trust signal toward C, weighted by how much A trusts B. The global trust
vector is the principal eigenvector of the normalised trust matrix — computed iteratively
until convergence.

This is the architecture used in P2P reputation systems (Gnutella, BitTorrent) and is the
theoretical foundation of Google's PageRank.

**Why this matters for the future:**
As AI agent meshes scale — hundreds of specialised agents interoperating, each unknown to
most others — direct attestation history between any two agents will be sparse. A new agent
entering the mesh has no direct attestations. EigenTrust allows the mesh to bootstrap trust
via transitivity: "I haven't worked with you directly, but three agents I trust have, and
they all endorsed you."

**Current state:** Implemented and opt-in. `EigenTrustComputer` ships with the extension and runs as a post-pass after the Bayesian Beta computation when enabled. The eigenvector computation requires a meaningful attestation network — sparse data produces unreliable global scores.

**Who will benefit:**
Large-scale decentralised AI agent networks, federated multi-organisation systems where no
central authority can be trusted to assign reputations, and cross-enterprise agent
coordination (e.g. supply chain automation spanning multiple companies).

**Enable when:** `quarkus.ledger.trust-score.eigentrust.enabled=true`. The agent mesh is large enough that direct attestation coverage is sparse and transitive propagation adds signal. For most organisations, direct Bayesian Beta scoring suffices until the actor count exceeds dozens of cross-attesting participants.

**Don't dismiss it:** This feature is not here because it is fashionable. It is here because
direct trust scoring without transitivity does not scale to large agent meshes. The
foundation (immutable log, peer attestation, Bayesian Beta) is being built correctly so that
EigenTrust can be layered on without architectural rework.

---

## The Supplement System

### ComplianceSupplement and ProvenanceSupplement ★★★★★

Supplements are the mechanism by which `quarkus-ledger` avoids the failure mode of "one
table with 40 nullable columns." Every ledger entry needs a core set of fields (actor,
sequence, timestamp, hash). No entry needs all the optional fields. Putting all optional
fields on the base entity creates schema bloat, query noise, and tight coupling between the
extension and domain concerns.

Instead, optional cross-cutting field sets live in separate joined tables:

| Supplement | Table | Fields |
|---|---|---|
| `ComplianceSupplement` | `ledger_supplement_compliance` | `planRef`, `rationale`, `evidence`, `detail`, `decisionContext`, `algorithmRef`, `confidenceScore`, `contestationUri`, `humanOverrideAvailable` |
| `ProvenanceSupplement` | `ledger_supplement_provenance` | `sourceEntityId`, `sourceEntityType`, `sourceEntitySystem` |

OTel trace linkage (`traceId`) and causal chaining (`causedByEntryId`) are core
fields on every `LedgerEntry` — present without attaching a supplement, incurring no extra
table row. They were originally a supplement and were promoted to core when it became clear
every consumer in the ecosystem needed them.

**Zero overhead when unused:** if a consumer never calls `attach()`, no supplement rows are
written. The base entry remains lean. A consumer that only needs compliance snapshots pays
no schema cost for provenance.

**Two read paths:**

```java
// Fast: JSON snapshot pre-written into entry.supplementJson — no join
String json = entry.supplementJson;

// Typed: lazy join, only when you need a specific field
Optional<ComplianceSupplement> cs = entry.compliance();
```

**Why this matters:**
The supplement pattern solves a real schema evolution problem. Enterprises add
`quarkus-ledger` and then, six months later, face an EU AI Act audit. They need compliance
snapshots. Without supplements, adding those fields requires a migration touching the core
`ledger_entry` table — potentially a multi-hour operation on a large ledger. With
supplements, the new `ledger_supplement_compliance` table is simply added; existing entries
are unaffected; new entries opt in explicitly.

This is the correct approach for a platform extension that must serve multiple consumers
with divergent requirements.

---

## Capability Selection Guide

Use this matrix to decide which capabilities to enable for a given consumer:

| Consumer type | Core log | Hash chain | MMR | Decision snapshots | Privacy | Provenance | Attestation | Trust scoring |
|---|---|---|---|---|---|---|---|---|
| Simple internal audit (no PII) | ✅ | ✅ | optional | — | — | optional | optional | — |
| Human workflow (HR, approvals) | ✅ | ✅ | optional | if automated | ✅ | ✅ | ✅ | optional |
| AI-assisted decisions (EU) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| AI agent mesh | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Financial / regulated trade | ✅ | ✅ | ✅ | if algo-driven | ✅ | ✅ | ✅ | optional |
| MLOps pipeline audit | ✅ | ✅ | optional | ✅ | optional | ✅ | optional | — |
| Machine-to-machine integration | ✅ | ✅ | optional | — | — | ✅ | optional | — |

**Decision rules:**

- Any EU-facing system with human actors → enable privacy pseudonymisation
- Any automated decision affecting individuals → attach `ComplianceSupplement`
- Any entry driven by an upstream system → attach `ProvenanceSupplement`
- Multiple actors reviewing each other's work → enable attestation
- Attestation volume is meaningful → enable trust scoring
- External auditors must verify integrity → consider MMR + signed checkpoints
