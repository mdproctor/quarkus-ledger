# Consumer Privacy Obligations

`casehub-ledger` provides the privacy infrastructure — pseudonymisation, token severing,
sanitisation hooks. Using it correctly is the consumer's responsibility. This document
describes what each consumer must understand and decide before going to production with
any system that records human actor identities.

---

## The Core Tension

An immutable audit ledger and GDPR's right to erasure (Art.17) are structurally in
conflict: the ledger is designed never to delete records; GDPR requires personal data to
be erasable on request. The extension resolves this tension through **pseudonymisation** —
storing tokens instead of raw identities, backed by a detachable mapping. Severing the
mapping makes the raw identity unrecoverable from the log. The audit record survives;
the link to a real person is removed.

This approach is consistent with EDPB Guidelines 05/2019 on pseudonymisation.

---

## Decision 1 — Is `actorId` personal data?

`actorId` is the identity of the actor who performed an action. If it contains or can be
linked to a natural person — a username, email address, employee ID, or any identifier
that resolves to a named individual — it is personal data under GDPR.

**Most enterprise deployments: yes.** Human workflow systems (Tarkus), user-facing AI
decision services, and any system where a human's name or account ID appears as `actorId`
must treat this field as personal data.

**Machine-to-machine deployments: possibly not.** If every actor is a service account,
system identity, or non-personal agent ID with no link to a natural person, `actorId` may
not be personal data. Confirm with your DPO.

**Action required:** If `actorId` is personal data, enable tokenisation:

```properties
quarkus.ledger.identity.tokenisation.enabled=true
```

This activates `InternalActorIdentityProvider`, which replaces raw `actorId` values with
opaque UUID tokens on every write. The token-to-identity mapping is stored in the
`actor_identity` table (V1004). Queries using `findByActorId()` translate transparently
via `tokeniseForQuery()`.

---

## Decision 2 — Is `subjectId` personal data?

`subjectId` is the aggregate identifier — the UUID that scopes an entry's sequence and
Merkle chain. Typically this is a domain object ID (order UUID, work item UUID, channel
UUID) with no direct link to a natural person.

However, if your aggregate UUID is derived from or can be linked to a natural person (e.g.
a user account UUID used directly as `subjectId`), it becomes personal data in context.

The extension does not tokenise `subjectId` — it is a structural field used for sequence
numbering, chain scoping, and Merkle frontier management. If `subjectId` is personal data
in your domain, you must handle that at the consumer level, typically by using an
intermediate aggregate UUID that is not directly derivable from the person's identity.

---

## Decision 3 — Does `decisionContext` contain PII?

`ComplianceSupplement.decisionContext` is a JSON blob capturing the state of a decision
at the moment it was made. This is intentionally rich — it should contain the information
the algorithm actually used. In practice, that often includes personal data: credit score
inputs, medical record references, employment history signals.

The extension provides a `DecisionContextSanitiser` SPI for stripping or redacting PII
from `decisionContext` before it reaches the database. The default implementation is
pass-through — it does nothing.

**You must implement `DecisionContextSanitiser`** if your `decisionContext` JSON contains
any of the following:
- Names, email addresses, phone numbers
- Financial data (account numbers, income figures)
- Health or biometric data
- Location data
- Any identifier that can be linked to a specific person

**Implementation:**

```java
@ApplicationScoped
public class MyDecisionContextSanitiser implements DecisionContextSanitiser {

    @Override
    public String sanitise(String decisionContextJson) {
        // Strip or redact PII fields before the JSON reaches the database.
        // The returned string is what gets stored — return null to suppress entirely.
        return JsonSanitiser.redact(decisionContextJson, "email", "name", "ssn");
    }
}
```

Because `DecisionContextSanitiser` is a `@DefaultBean`, your implementation replaces the
pass-through automatically — no configuration change needed.

> **Important:** An immutable log that stores raw PII in `decisionContext` creates a
> permanent privacy liability. The Art.22 requirement is to record *what logic was used*,
> not *who the subject is*. Strip identifying information and retain algorithmic inputs.

---

## Decision 4 — Do you need a custom `ActorIdentityProvider`?

The built-in `InternalActorIdentityProvider` (activated by
`quarkus.ledger.identity.tokenisation.enabled=true`) stores token-to-identity mappings
in the local `actor_identity` table. This is appropriate when:

- The extension manages its own identity lifecycle
- You don't have a centralised identity management system
- You want the simplest path to GDPR Art.17 compliance

**Implement a custom `ActorIdentityProvider`** when:

- You have an existing identity management system (LDAP, Keycloak, external vault) that
  should own the token mapping
- You need to support cross-service identity resolution
- You need token formats other than UUID

```java
@ApplicationScoped
public class KeycloakActorIdentityProvider implements ActorIdentityProvider {

    @Override
    public String tokenise(String rawActorId) {
        return keycloakClient.getPseudonymFor(rawActorId); // create if absent
    }

    @Override
    public String tokeniseForQuery(String rawActorId) {
        return keycloakClient.lookupPseudonym(rawActorId); // read-only, null if absent
    }

    @Override
    public Optional<String> resolve(String token) {
        return keycloakClient.resolveToken(token);
    }

    @Override
    public void erase(String rawActorId) {
        keycloakClient.deletePseudonym(rawActorId);
    }
}
```

A custom `ActorIdentityProvider` replaces the built-in implementation via `@DefaultBean`
semantics — no configuration change needed, and
`quarkus.ledger.identity.tokenisation.enabled` has no effect when a custom bean is present.

---

## Handling GDPR Art.17 Erasure Requests

When a data subject invokes their right to erasure, inject `LedgerErasureService` and
call `erase()` with the raw actor identity:

```java
@Inject LedgerErasureService erasureService;

@DELETE
@Path("/actors/{actorId}/identity")
@Transactional
public ErasureResult eraseActor(@PathParam("actorId") String rawActorId) {
    return erasureService.erase(rawActorId);
}
```

`ErasureResult` contains:
- `actorIdentity` — the `ActorIdentity` record that was severed (or empty if not found)
- `mappingFound` — whether a token mapping existed
- `affectedEntryCount` — how many ledger entries carried this actor's token

**What erasure does:** Deletes the token-to-identity mapping. The token UUID in existing
`ledger_entry` rows becomes permanently unresolvable. The Merkle hash chain is intact —
the entries themselves are not deleted, only the link to the real identity is severed.

**What erasure does not do:**
- It does not delete ledger entries
- It does not remove the token from the `digest` / Merkle computation (the token is not
  personal data — it is an opaque UUID)
- It does not affect `decisionContext` content — this is why `DecisionContextSanitiser`
  matters: if raw PII reached `decisionContext`, erasure does not help

---

## Configuration Summary

| Key | Default | When to set |
|---|---|---|
| `quarkus.ledger.identity.tokenisation.enabled` | `false` | Enable when `actorId` is personal data and you don't have a custom `ActorIdentityProvider` |

---

## Responsibility Boundary

| Responsibility | Owner |
|---|---|
| Pseudonymising `actorId` on write | Extension (`ActorIdentityProvider`) |
| Pseudonymising `attestorId` on write | Extension (`ActorIdentityProvider`) |
| Sanitising `decisionContext` PII | Consumer (`DecisionContextSanitiser`) |
| Ensuring `subjectId` is not personal data | Consumer |
| Exposing the erasure endpoint | Consumer |
| DPA notification on erasure | Consumer |
| Documenting retention periods | Consumer |
| Risk classification under EU AI Act Annex III | Consumer |

---

## Related Documents

| Document | Contents |
|---|---|
| [CAPABILITIES.md](CAPABILITIES.md) | Applicability ratings for privacy and all other capabilities |
| [compliance/EU-AI-ACT-ART12.md](compliance/EU-AI-ACT-ART12.md) | Art.12 record-keeping obligations |
| [AUDITABILITY.md](AUDITABILITY.md) | Axiom 7 (Privacy Compatibility) assessment |
