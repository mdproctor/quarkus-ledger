# Merkle Tree Upgrade — Design Spec

**Date:** 2026-04-18
**Issue:** #11
**ADR:** [0002 — Merkle Tree Internal Structure](../../../adr/0002-merkle-tree-internal-structure.md)
**Closes:** Axiom 4 (Verifiability) in `docs/AUDITABILITY.md`

---

## Goal

Replace the linear SHA-256 hash chain with a Merkle Mountain Range (stored frontier)
that provides:

1. O(log N) inclusion proofs for any entry — without loading the full chain
2. A publishable tree root — enabling external auditors to verify integrity without
   DB access, using only a signed checkpoint and an inclusion proof

No existing installations — schema is redesigned clean.

---

## Schema

### Changes to `ledger_entry`

- **Drop `previous_hash`** — the chain relationship is now expressed by the Merkle tree
  structure, not by chaining hashes within each entry.
- **`digest` semantics change** — was `SHA-256(previousHash | canonicalFields)`;
  becomes `SHA-256(0x00 | canonicalFields)`. The `0x00` byte is the RFC 9162
  domain separator that distinguishes leaf hashes from internal node hashes.
  Column stays; meaning changes.

### New table: `ledger_merkle_frontier`

```sql
CREATE TABLE ledger_merkle_frontier (
    id          UUID        PRIMARY KEY,
    subject_id  UUID        NOT NULL,
    level       INTEGER     NOT NULL,
    hash        VARCHAR(64) NOT NULL,
    UNIQUE (subject_id, level)
);
CREATE INDEX idx_merkle_frontier_subject ON ledger_merkle_frontier (subject_id);
```

Each row is one frontier node — the root hash of a perfect binary subtree of `2^level`
leaves for a given `subjectId`. A subject with N entries has exactly `Integer.bitCount(N)`
rows at any moment (one per set bit in N's binary representation). At 1 million entries:
at most 20 rows.

Schema lives in the base migration files (no incremental migrations — no existing
installations).

---

## Core Algorithm — `LedgerMerkleTree`

Replaces `LedgerHashChain`. Pure static utility, no CDI, no side effects.

### Hash functions

```
leafHash(entry)         = SHA-256(0x00 | canonicalFields)
internalHash(left, right) = SHA-256(0x01 | left | right)
```

Canonical fields (unchanged from current chain):
`subjectId|seqNum|entryType|actorId|actorRole|occurredAt`

### Append

Called after each entry is persisted. Input: the new leaf hash + the current frontier
node set for the subject.

```
carry = leafHash, level = 0
while frontier has a node at level:
    carry = internalHash(frontier[level].hash, carry)
    remove frontier[level]
    level++
insert (level, carry) into frontier
```

Binary-carry propagation — typically updates 1–2 frontier rows per append.

### Tree root

Fold frontier nodes from highest level to lowest:

```
sort frontier by level DESC
root = frontier[0].hash
for each remaining node (level DESC):
    root = internalHash(node.hash, root)
```

If only one frontier node, it is the root directly.

### Inclusion proof

Given a 0-based entry index `k` and tree size `N`, compute the sibling hashes
along the path from leaf `k` to the root. Requires fetching O(log N) `digest`
values from `ledger_entry`. Returns a `List<ProofStep>` where each step is
`{hash, side}`. Verification requires no DB access — only the proof steps and
the known tree root.

---

## New Components

### `LedgerMerkleFrontier` (entity)

JPA entity mapping `ledger_merkle_frontier`. Fields: `id` (UUID), `subjectId` (UUID),
`level` (int), `hash` (String).

### `LedgerMerkleFrontierRepository` (SPI + JPA impl)

- `findBySubjectId(UUID subjectId)` → `List<LedgerMerkleFrontier>`
- `upsert(LedgerMerkleFrontier node)` — insert or replace by `(subjectId, level)`
- `deleteBySubjectIdAndLevel(UUID subjectId, int level)`

### `LedgerMerkleTree` (pure static utility)

- `leafHash(LedgerEntry entry)` → String
- `internalHash(String left, String right)` → String
- `append(String leafHash, List<LedgerMerkleFrontier> frontier)` → updated frontier
- `treeRoot(List<LedgerMerkleFrontier> frontier)` → String
- `inclusionProof(int entryIndex, int treeSize, List<String> leafHashes)` → `InclusionProof`
  (pure computation — caller fetches leaf hashes from `ledger_entry.digest`)

### `InclusionProof` (record)

```java
record InclusionProof(
    UUID entryId,
    int entryIndex,
    int treeSize,
    String leafHash,
    List<ProofStep> siblings,
    String treeRoot
) {}

record ProofStep(String hash, Side side) {}
enum Side { LEFT, RIGHT }  // defined in ProofStep.java
```

### `LedgerVerificationService` (CDI bean, `@ApplicationScoped`)

Auto-activated — no consumer configuration required.

```java
String treeRoot(UUID subjectId);          // reads frontier only
InclusionProof inclusionProof(UUID entryId);  // O(log N) entry reads
boolean verify(UUID subjectId);           // full recompute from leaves
```

### Write path integration

In `JpaLedgerEntryRepository`, after persisting an entry:

1. `entry.digest = LedgerMerkleTree.leafHash(entry)`
2. Load frontier for `entry.subjectId`
3. `updatedFrontier = LedgerMerkleTree.append(entry.digest, frontier)`
4. Persist updated frontier rows (upsert)

All within the same transaction as the entry write. Frontier is always consistent
with persisted entries.

---

## External Publishing — `LedgerMerklePublisher`

CDI bean activated only when `quarkus.ledger.merkle.publish.url` is configured.
Zero cost when absent.

Called after each frontier update. Publishing is **async and best-effort** — does
not block the write transaction. Failure is logged; the entry persist is unaffected.

### Checkpoint format (tlog-checkpoint / c2sp.org)

```
io.casehub.ledger/v1
<subjectId>
<treeSize>
<base64(rootHash)>

— <key-id> <base64(Ed25519 signature)>
```

The Ed25519 signature covers all lines above the blank line (UTF-8 bytes).
Signing uses `java.security` EdDSA — native in Java 15+, zero external dependencies.

### Config

```
quarkus.ledger.merkle.publish.url          # POST endpoint — enables publisher when set
quarkus.ledger.merkle.publish.private-key  # path to Ed25519 private key PEM file
quarkus.ledger.merkle.publish.key-id       # opaque string identifying the public key
```

The extension never generates or manages the key pair. Operator generates the
Ed25519 keypair; public key is published out-of-band. Receivers verify using
only the public key — no shared secret.

---

## Testing

### Unit tests — `LedgerMerkleTreeTest`

No DB, no Quarkus. Tests against fixed test vectors:

- `leafHash()` — correct domain-separated SHA-256 for known input
- `append()` — correct frontier for 1, 2, 3, 4, 5, 7, 8 entries (covers all
  carry-propagation cases)
- `treeRoot()` — correct root for each frontier state above
- `inclusionProof()` — proof verifies for: first entry, last entry, middle entry,
  entry at power-of-2 boundary

### Integration tests — `@QuarkusTest`

Real DB (H2 in-memory via Quarkus test profile):

- After N entries, frontier has `Integer.bitCount(N)` rows
- `LedgerVerificationService.treeRoot(subjectId)` is consistent with frontier
- `inclusionProof(entryId)` returns a proof that verifies without further DB access
- `verify(subjectId)` returns `true` for untampered sequence
- `verify(subjectId)` returns `false` after direct DB manipulation of a `digest`

### Publisher tests — unit with mock HTTP server

- Checkpoint text format is correct and parseable
- Ed25519 signature verifies against the corresponding public key
- No HTTP calls when `publish.url` is absent

---

## What Is Deleted

- `LedgerHashChain.java` — replaced entirely by `LedgerMerkleTree`
- `previous_hash` column from `ledger_entry`

---

## File Map

```
runtime/src/main/java/io/casehub/ledger/runtime/
├── model/
│   └── LedgerMerkleFrontier.java          — new entity
├── repository/
│   ├── LedgerMerkleFrontierRepository.java — new SPI
│   └── jpa/
│       └── JpaLedgerMerkleFrontierRepository.java — new JPA impl
└── service/
    ├── LedgerMerkleTree.java              — replaces LedgerHashChain
    ├── LedgerVerificationService.java     — new CDI bean
    ├── LedgerMerklePublisher.java         — new CDI bean (conditional)
    └── model/
        ├── InclusionProof.java            — new record
        └── ProofStep.java                 — new record
```
