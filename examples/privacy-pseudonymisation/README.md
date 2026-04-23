# Example: Privacy and Pseudonymisation

Demonstrates the full GDPR privacy lifecycle using a credit application review service as the domain.

## What this demonstrates

- **Actor identity pseudonymisation** — raw identities (email addresses, HR IDs, LLM persona names) are replaced with UUID tokens at write time. No call-site code needed: `JpaLedgerEntryRepository.save()` handles tokenisation automatically when `quarkus.ledger.identity.tokenisation.enabled=true`.
- **`ComplianceSupplement.detail`** — the free-text explanation field for decision logic, often absent from example code. Documents *why* a model produced the result it did, in plain language that satisfies GDPR Art.22's "meaningful information" requirement.
- **`ProvenanceSupplement.agentConfigHash`** — SHA-256 hex digest of the LLM agent's configuration (e.g. `CLAUDE.md` + system prompts) at session start. Enables forensic detection of configuration drift within a persona version without affecting trust scoring.
- **GDPR Art.17 erasure via `LedgerErasureService`** — severs the token→identity mapping. The audit record survives intact; the raw identity becomes permanently unresolvable.

## The GDPR privacy problem

Audit logs that store raw personal identifiers (names, emails, employee IDs) create a tension with GDPR: you need the log to be immutable and tamper-evident, but a data subject can demand erasure of their personal data. Deleting or altering ledger entries breaks the hash chain.

Pseudonymisation solves this. Each actor identity is stored as a UUID token, backed by a mapping table. On erasure, only the mapping row is deleted — the ledger entry retains the token, keeping the hash chain intact, but the link from token to person is permanently severed.

## Key code patterns

### Automatic pseudonymisation

```java
entry.actorId = "alice@example.com";  // set raw identity
repo.save(entry);                      // stored actorId is now a UUID token
```

No additional code is required. The repository tokenises on save.

### ComplianceSupplement with detail

```java
ComplianceSupplement cs = new ComplianceSupplement();
cs.algorithmRef = "credit-risk-model-v3";
cs.confidenceScore = riskScore;
cs.humanOverrideAvailable = true;
cs.contestationUri = "https://example.com/decisions/" + id + "/challenge";
cs.decisionContext = "{\"riskScore\":0.82}";
cs.detail = "Risk score computed from income, credit history, and debt ratio. " +
            "Scores above 0.7 trigger mandatory human review.";  // <-- the detail field
entry.attach(cs);
```

### ProvenanceSupplement with agentConfigHash

```java
ProvenanceSupplement ps = new ProvenanceSupplement();
ps.sourceEntitySystem = "credit-risk-platform";
ps.sourceEntityType = "CreditApplication";
ps.sourceEntityId = applicationId.toString();
ps.agentConfigHash = computeSha256(claudeMd + systemPrompts);  // 64 hex chars
entry.attach(ps);
```

### GDPR Art.17 erasure

```java
ErasureResult result = erasureService.erase("alice@example.com");
// result.mappingFound() == true  → mapping was found and severed
// result.affectedEntryCount()    → how many ledger entries carried the token
// alice's token remains in ledger entries — audit record intact
// the token is now permanently unresolvable
```

## Running

```bash
# Tests
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test

# Dev mode
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn quarkus:dev
```

## Configuration

| Property | Value | Purpose |
|---|---|---|
| `quarkus.ledger.identity.tokenisation.enabled` | `true` | Activates UUID token pseudonymisation |
| `quarkus.ledger.hash-chain.enabled` | `true` | Merkle leaf hashes on every entry |
| `quarkus.ledger.decision-context.enabled` | `true` | Enables decisionContext field |
| `quarkus.arc.selected-alternatives` | `JpaLedgerEntryRepository` | Activates built-in JPA repository |
