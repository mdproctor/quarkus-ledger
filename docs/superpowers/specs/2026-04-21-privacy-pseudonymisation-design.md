# Privacy / Pseudonymisation Design Spec

**Date:** 2026-04-21
**Status:** Approved

---

## Problem

GDPR Article 17 gives data subjects the right to erasure. An immutable append-only audit
ledger cannot delete entries without breaking the Merkle hash chain. This tension is the
Axiom 7 gap in `docs/AUDITABILITY.md`.

The solution is pseudonymisation: store tokens instead of raw actor identities in the
ledger, backed by a detachable mapping. On erasure, sever the mapping — ledger entries
retain the token but it becomes permanently unresolvable. The hash chain is intact;
the personal data link is gone.

Every organisation worldwide has different legal, operational, and identity management
requirements. The extension must not pick a strategy — it makes the strategy pluggable.

---

## Design Constraint

> **If a consumer does not configure pseudonymisation, nothing changes.** All SPIs default
> to pass-through. No new config required. The `actor_identity` table exists but is empty.
> Existing consumers see zero behaviour change.

---

## Architecture

Three components, all independently pluggable:

```
ActorIdentityProvider (SPI)          DecisionContextSanitiser (SPI)
       ↑                                        ↑
InternalActorIdentityProvider        pass-through default
(built-in, config-gated)

Both called by LedgerEntryRepository and JpaLedgerEntryRepository before persist.

LedgerErasureService (CDI bean)
  → delegates to ActorIdentityProvider.erase()
  → returns ErasureResult
```

---

## Component 1 — `ActorIdentityProvider` SPI

```java
package io.casehub.ledger.runtime.privacy;

public interface ActorIdentityProvider {
    /** Called on write. Returns a token to store in place of rawActorId. Default: identity. */
    String tokenise(String rawActorId);

    /** Maps a stored token back to the real identity. Default: Optional.of(token). */
    Optional<String> resolve(String token);

    /** Severs the token→identity mapping. Called on erasure. Default: no-op. */
    void erase(String rawActorId);
}
```

**Default implementation:** pass-through — `tokenise` returns rawActorId unchanged,
`resolve` returns `Optional.of(token)`, `erase` is a no-op. Registered as `@Alternative`
so custom beans replace it without configuration changes.

**Built-in implementation: `InternalActorIdentityProvider`**
- Activated when `quarkus.ledger.identity.tokenisation.enabled=true` via a CDI producer
  (`LedgerPrivacyProducer`) that reads config and returns either the built-in or
  pass-through impl — consumers never need to declare a bean to switch it on
- Backed by the `actor_identity` table (V1004)
- `tokenise`: looks up existing token for rawActorId; if none, generates a random UUID token and inserts a row
- `resolve`: queries `actor_identity` by token; returns `Optional.empty()` if row is missing (severed)
- `erase`: deletes the row where `actor_id = rawActorId`; the stored token becomes permanently unresolvable

---

## Component 2 — `DecisionContextSanitiser` SPI

```java
package io.casehub.ledger.runtime.privacy;

public interface DecisionContextSanitiser {
    /** Called before ComplianceSupplement.decisionContext is persisted. Default: identity. */
    String sanitise(String decisionContextJson);
}
```

**Default implementation:** pass-through. Consumers needing PII scrubbing in the JSON
blob provide their own CDI bean — the extension stays out of their JSON processing logic.

---

## Component 3 — `LedgerErasureService`

```java
@ApplicationScoped
public class LedgerErasureService {

    public record ErasureResult(
            String rawActorId,
            boolean mappingFound,
            long affectedEntryCount) {}

    public ErasureResult erase(String rawActorId);
}
```

`affectedEntryCount` counts ledger entries that referenced the severed token —
informational only. Entries are not deleted; their token is simply unresolvable.
Consumers should log erasure requests in their own audit trail.

---

## Write Path — Three Tokenisation Points

`LedgerEntryRepository.save()` and attestation persist both call the SPIs:

1. **`LedgerEntry.actorId`** — tokenised via `ActorIdentityProvider.tokenise()` before every `save()`
2. **`LedgerAttestation.attestorId`** — tokenised on attestation persist in `JpaLedgerEntryRepository`
3. **`ComplianceSupplement.decisionContext`** — run through `DecisionContextSanitiser.sanitise()` before persist

Query-time: `findByActorId(rawActorId, from, to)` calls `tokenise()` internally before
querying the database — callers always use real identities; tokens are an internal detail.

---

## Schema — V1004

```sql
-- actor_identity: optional pseudonymisation mapping table.
-- Always created. Empty when tokenisation is disabled.
-- UNIQUE (actor_id) ensures the same rawActorId always maps to the same token.

CREATE TABLE actor_identity (
    token        VARCHAR(255) NOT NULL,
    actor_id     VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    CONSTRAINT pk_actor_identity PRIMARY KEY (token),
    CONSTRAINT uq_actor_identity_actor_id UNIQUE (actor_id)
);
```

---

## Config

One key added to `LedgerConfig.IdentityConfig`:

| Key | Default | Description |
|---|---|---|
| `quarkus.ledger.identity.tokenisation.enabled` | `false` | Activate `InternalActorIdentityProvider` |

Organisations with external identity systems (LDAP, IdP) provide their own
`ActorIdentityProvider` CDI bean and ignore this key entirely.

---

## Erasure Flow

1. Consumer calls `LedgerErasureService.erase("alice@example.com")`
2. Service queries `actor_identity` for the token mapped to `alice@example.com` — records `affectedEntryCount`
3. Calls `ActorIdentityProvider.erase("alice@example.com")`
4. Built-in impl deletes the row from `actor_identity`
5. All ledger entries retain their token (`tok:abc123`) — hash chain intact
6. `resolve("tok:abc123")` now returns `Optional.empty()` — Alice is unresolvable
7. Returns `ErasureResult(rawActorId="alice@example.com", mappingFound=true, affectedEntryCount=N)`

---

## Scope

| File | Change |
|---|---|
| `runtime/.../privacy/ActorIdentityProvider.java` | **Create** — SPI interface |
| `runtime/.../privacy/DecisionContextSanitiser.java` | **Create** — SPI interface |
| `runtime/.../privacy/PassThroughActorIdentityProvider.java` | **Create** — default impl |
| `runtime/.../privacy/PassThroughDecisionContextSanitiser.java` | **Create** — default impl |
| `runtime/.../privacy/InternalActorIdentityProvider.java` | **Create** — built-in token impl |
| `runtime/.../service/LedgerErasureService.java` | **Create** — erasure CDI bean |
| `runtime/.../repository/LedgerEntryRepository.java` | Inject `ActorIdentityProvider`; call in `save()` and attestation persist |
| `runtime/.../repository/jpa/JpaLedgerEntryRepository.java` | Tokenise `actorId` and `attestorId`; sanitise `decisionContext` |
| `runtime/.../config/LedgerConfig.java` | Add `IdentityConfig` sub-interface |
| `runtime/.../resources/db/migration/V1004__actor_identity.sql` | **Create** — new table |
| `runtime/src/test/.../privacy/InternalActorIdentityProviderTest.java` | **Create** — unit tests |
| `runtime/src/test/.../privacy/LedgerErasureServiceIT.java` | **Create** — integration tests |

---

## Tests

**Unit (`InternalActorIdentityProviderTest`):**
- `tokenise` same actorId twice → same token returned both times
- `tokenise` two different actorIds → different tokens
- `resolve` known token → returns rawActorId
- `resolve` after `erase` → returns `Optional.empty()`
- `resolve` unknown token → returns `Optional.empty()`
- `erase` known actorId → `mappingFound=true` in result
- `erase` unknown actorId → `mappingFound=false` in result

**Integration (`LedgerErasureServiceIT`):**
- Full round-trip: save entry with raw actorId → stored token differs from raw → `findByActorId(rawActorId)` returns entry → erase → `findByActorId(rawActorId)` returns empty
- Pass-through default: no config set → raw actorId stored unchanged → existing tests unaffected
- `attestorId` on `LedgerAttestation` tokenised alongside `actorId`
- `decisionContext` passed through `DecisionContextSanitiser` before persist

---

## What This Does NOT Cover

- Pseudonymisation of `subjectId` (the aggregate UUID) — typically not personal data; consumer's responsibility if it is
- Enforcement of PII-free `decisionContext` — `DecisionContextSanitiser` provides the hook; the extension cannot validate JSON content
- Right of access (Art.15) or rectification (Art.16) — out of scope for this extension
