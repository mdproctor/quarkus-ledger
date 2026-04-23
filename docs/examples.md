# Examples

Each example is a standalone Maven project that demonstrates one capability of `quarkus-ledger`.
They are runnable with `mvn quarkus:dev` and include integration tests. Use them as
production-grade starting points for your own integration — copy the pattern, adapt the domain.

---

## The Supplement System

Supplements are optional structured extensions to a `LedgerEntry`. When unused, they add no
columns to the base table and no overhead to queries — each supplement type lives in its own
joined table. An entry can carry multiple supplements simultaneously.

### ComplianceSupplement

`ComplianceSupplement` carries the fields required by GDPR Article 22 and EU AI Act Article 12
for automated decisions. Attach it to any entry where the system made a decision that could
have legal or significant effects on an individual.

| Field | Purpose |
|---|---|
| `decisionContext` | JSON snapshot of system state at decision time. The "what did it know?" evidence — satisfies Arts.13–15 right to information about personal data used. |
| `algorithmRef` | Which model or rule version produced the decision. Critical for post-hoc audit when a model is updated or rolled back. |
| `confidenceScore` | Numerical certainty [0.0–1.0]. Required for meaningful explainability — "the model was 94% confident" is informative; a binary outcome alone is not. |
| `humanOverrideAvailable` | GDPR Art.22(2)(b) — explicit boolean flag documenting whether a human can intervene in this decision pathway. |
| `contestationUri` | Where to submit a challenge. Art.22 requires a contestation mechanism; this field makes it machine-readable and directly linkable. |
| `rationale` | Narrative explanation of the decision in plain language. |
| `planRef` | Reference to the policy or rule set this decision applied — e.g. `"classification-policy-v3"`. |
| `evidence` | Supporting evidence string — e.g. a document ID or feature vector hash. |
| `detail` | Additional context explaining the methodology or constraints — e.g. "Scores above 0.7 trigger mandatory human review." |

### ProvenanceSupplement

`ProvenanceSupplement` records the upstream source of a ledger entry — which external entity,
in which system, triggered this event. It enables data lineage across system boundaries: an
MLOps pipeline can trace a decision back to the workflow instance that initiated it, or an
audit tool can correlate ledger entries with the upstream CRM or task system.

| Field | Purpose |
|---|---|
| `sourceEntityId` | Identifier of the upstream entity that triggered this ledger entry — e.g. a WorkItem UUID, a CRM record ID. |
| `sourceEntityType` | Type of that entity — e.g. `"CreditApplication"`, `"WorkflowInstance"`. Enables consumers to join against the right upstream table. |
| `sourceEntitySystem` | The system that owns it — e.g. `"quarkus-flow"`, `"credit-platform"`. Scopes the `sourceEntityId` namespace. |
| `agentConfigHash` | SHA-256 hex digest of the LLM agent's configuration (system prompts, tool list, model parameters) at session start. Detects configuration drift within a versioned persona: if the hash changes between entries, the agent's behaviour may have shifted even without a version bump. |

---

## Runnable Examples

### [order-processing](../examples/order-processing/)

**Demonstrates:** JPA JOINED inheritance, per-subject hash chain, attestations, audit query API

**Key pattern:**
```java
@Entity
@Table(name = "order_ledger_entry")
@DiscriminatorValue("ORDER")
public class OrderLedgerEntry extends LedgerEntry {
    @Column(name = "order_id", nullable = false)
    public UUID orderId;

    @Column(name = "command_type")
    public String commandType;  // "PlaceOrder", "ShipOrder"

    @Column(name = "event_type")
    public String eventType;    // "OrderPlaced", "OrderShipped"

    @Column(name = "order_status")
    public String orderStatus;  // snapshot at transition time
}
```

**Run:** `cd examples/order-processing && mvn quarkus:dev`

---

### [art22-decision-snapshot](../examples/art22-decision-snapshot/)

**Demonstrates:** GDPR Art.22 `ComplianceSupplement` — `algorithmRef`, `confidenceScore`,
`contestationUri`, `humanOverrideAvailable`

**Key pattern:**
```java
ComplianceSupplement cs = new ComplianceSupplement();
cs.algorithmRef           = "risk-model-v3";
cs.confidenceScore        = 0.88;
cs.contestationUri        = "https://example.com/challenge/" + subjectId;
cs.humanOverrideAvailable = true;
cs.decisionContext        = "{\"creditScore\":720}";
entry.attach(cs);
repo.save(entry);
```

**Run:** `cd examples/art22-decision-snapshot && mvn quarkus:dev`

---

### [art12-compliance](../examples/art12-compliance/)

**Demonstrates:** EU AI Act Art.12 retention enforcement, audit query API (`findByActorId`,
`findByTimeRange`)

**Key pattern:**
```java
// Query entries by actor within a time range — Art.12 retention audit
List<LedgerEntry> entries = repo.findByActorId(actorId, from, to);

// Query all entries within a retention window
List<LedgerEntry> all = repo.findByTimeRange(from, to);
```

**Run:** `cd examples/art12-compliance && mvn quarkus:dev`

---

### [merkle-verification](../examples/merkle-verification/)

**Demonstrates:** Merkle Mountain Range inclusion proofs, offline chain verification without
database access

**Key pattern:**
```java
// Get the current tree root for a subject
String root = verification.treeRoot(subjectId);

// Generate an inclusion proof for a specific entry
InclusionProof proof = verification.inclusionProof(entryId);

// Verify the proof offline — no DB, no service call required
boolean valid = LedgerMerkleTree.verifyProof(proof, root);
```

**Run:** `cd examples/merkle-verification && mvn quarkus:dev`

---

### [prov-dm-export](../examples/prov-dm-export/)

**Demonstrates:** W3C PROV-DM JSON-LD export from `LedgerProvExportService`, entries with
both `ProvenanceSupplement` and `ComplianceSupplement`, `causedByEntryId` causal chaining

**Key pattern:**
```java
// Entry 1: AI decision with ComplianceSupplement
e1.attach(complianceSupplement);
e1 = (ProvAuditEntry) repo.save(e1);

// Entry 2: upstream event with ProvenanceSupplement
e2.attach(provenanceSupplement);
repo.save(e2);

// Entry 3: caused by Entry 1 — appears as wasDerivedFrom edge in PROV graph
e3.causedByEntryId = e1.id;
repo.save(e3);

// Export full subject as PROV-DM JSON-LD
String jsonLd = exportService.exportSubject(subjectId);
```

**Run:** `cd examples/prov-dm-export && mvn quarkus:dev`

---

### [trust-score-routing](../examples/trust-score-routing/)

**Demonstrates:** CDI routing signals after trust score computation — `@Observes TrustScoreFullPayload`
(sync, full ranked list) and `@ObservesAsync TrustScoreComputedAt` (async, lightweight notification)

**Key pattern:**
```java
// Sync observer: rebuild ranked agent list on every full recompute
public void onScoresUpdated(@Observes TrustScoreFullPayload payload) {
    rankedAgents = payload.scores().stream()
            .sorted(Comparator.comparingDouble(s -> -s.trustScore))
            .map(s -> s.actorId)
            .toList();
}

// Async observer: log refresh signal without blocking the job thread
public CompletionStage<Void> onNotification(
        @ObservesAsync TrustScoreComputedAt notification) {
    log.infof("Scores refreshed at %s for %d actors",
            notification.computedAt(), notification.actorCount());
    return CompletableFuture.completedFuture(null);
}
```

**Run:** `cd examples/trust-score-routing && mvn quarkus:dev`

---

### [privacy-pseudonymisation](../examples/privacy-pseudonymisation/)

**Demonstrates:** Actor tokenisation (raw identity replaced with UUID token on save),
`ComplianceSupplement.detail`, `ProvenanceSupplement.agentConfigHash`, GDPR Art.17 erasure

**Key pattern:**
```java
// Raw identity — pseudonymised automatically by repo.save()
entry.actorId = "alice@example.com";

// detail explains the decision logic in plain language
cs.detail = "Risk score computed from income, credit history, and debt ratio. " +
        "Scores above 0.7 trigger mandatory human review.";

// agentConfigHash binds the entry to the agent's config at session start
ps.agentConfigHash = sha256HexOfSystemPromptAndToolList;

// Art.17: severs the token→identity mapping; audit record survives intact
ErasureResult result = erasureService.erase("alice@example.com");
```

**Run:** `cd examples/privacy-pseudonymisation && mvn quarkus:dev`

---

### [eigentrust-mesh](../examples/eigentrust-mesh/)

**Demonstrates:** EigenTrust transitive trust propagation — how `globalTrustScore` differs
from the local Bayesian `trustScore` when peers attest each other's work

**Key pattern:**
```java
// Three agents with different reliability — A endorsed, B mixed, C flagged
attest(AGENT_B, entryA, AttestationVerdict.SOUND,      0.9, t);
attest(AGENT_A, entryB, AttestationVerdict.CHALLENGED,  0.8, t);
attest(AGENT_A, entryC, AttestationVerdict.FLAGGED,     0.95, t);

// Run Bayesian Beta + EigenTrust power iteration
trustScoreJob.runComputation();

// trustScore: local Bayesian Beta (verdicts received by this actor)
// globalTrustScore: EigenTrust result (trust from trustworthy sources weighted higher)
List<ActorTrustScore> scores = trustRepo.findAll();
```

**Run:** `cd examples/eigentrust-mesh && mvn quarkus:dev`

---

### [otel-trace-wiring](../examples/otel-trace-wiring/)

**Demonstrates:** OTel trace auto-wiring — `LedgerEntry.traceId` populated from the active
span with zero call-site code

**Key pattern:**
```java
// No traceId code here — LedgerTraceListener reads the active OTel span on persist
repo.save(entry);

// traceId is set; response proves it persisted to the database
return Response.status(201)
        .entity(new EventResponse(entry.id, entry.traceId))
        .build();
```

**Run:** `cd examples/otel-trace-wiring && mvn quarkus:dev`

---

## Real-world reference implementations

| Project | Subclass | Domain |
|---|---|---|
| [quarkus-tarkus](https://github.com/mdproctor/quarkus-tarkus) | `WorkItemLedgerEntry` | Task lifecycle — create, claim, start, complete, reject, delegate |
| [quarkus-qhorus](https://github.com/mdproctor/quarkus-qhorus) | `AgentMessageLedgerEntry` | AI agent telemetry — tool calls with duration, token count, context refs |

Both include full integration test suites that exercise Merkle tree verification, sequence
numbering, decision context, attestations, and trust score computation.
