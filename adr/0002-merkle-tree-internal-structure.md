# 0002 — Merkle Tree Internal Structure for Hash Chain Upgrade

Date: 2026-04-17
Status: Accepted

## Context and Problem Statement

The current linear hash chain (`LedgerHashChain`) requires O(N) work to verify
any single entry — an auditor must load and recompute the entire chain from
genesis. As the ledger grows and external verifiability becomes a requirement
(Axiom 4), a more efficient structure is needed that supports O(log N) inclusion
proofs and publishable tree roots without DB access.

## Decision Drivers

* O(log N) inclusion proofs for any entry
* Publishable tree root for external verifiability (no DB access required)
* Zero schema overhead for consumers who don't use verification
* Upgrade path to full node storage if genuine demand emerges
* No existing installations — clean schema design, no migration constraints

## Considered Options

* **Option A** — Full node storage (~2N rows, all intermediate nodes persisted)
* **Option B** — Checkpoint windows (Merkle root per fixed window of N entries)
* **Option C** — Stored frontier / Merkle Mountain Range (log₂(N) rows per subject)

## Decision Outcome

Chosen option: **Option C — Stored frontier**, because it gives correct, complete
inclusion proofs for every entry immediately on append, with compact storage
(log₂(N) rows per subject), and is the algorithm specified in RFC 9162. The tree
root is the Merkle hash of the frontier nodes and is immediately publishable.
Proof generation fetches O(log N) entry digests at query time — acceptable for
any realistic audit log size.

### Positive Consequences

* Compact frontier table — at most log₂(N) rows per subject (≤20 rows at 1M entries)
* Every entry has a valid inclusion proof immediately after append
* Tree root = frontier hash — directly publishable for external verification
* Matches RFC 9162 algorithm; well-understood and auditable by third parties
* Upgrade path to Option A (pre-stored nodes) is additive if future customers require it

### Negative Consequences / Tradeoffs

* Inclusion proof generation fetches O(log N) entry `digest` values from `ledger_entry` at
  query time rather than reading pre-stored node hashes
* Slightly more complex append logic than checkpoint windows

## Pros and Cons of the Options

### Option A — Full node storage

* ✅ Inclusion proof is O(1) to fetch (walk pre-stored nodes)
* ❌ ~2N rows in node table — storage cost proportional to total entries
* ❌ Complex node management on every append
* ❌ Highest schema surface area

### Option B — Checkpoint windows

* ✅ Very simple to implement
* ✅ Minimal schema addition
* ❌ Entries in the current incomplete window have no Merkle proof until window closes
* ❌ Windowed proofs only — not per-entry proofs at all times

### Option C — Stored frontier (Merkle Mountain Range)

* ✅ log₂(N) rows per subject — compact
* ✅ Every entry has an immediate, correct inclusion proof
* ✅ RFC 9162 compatible
* ✅ Tree root publishable at any time
* ❌ Proof generation reads O(log N) entry rows at query time

## Links

* RFC 9162: Certificate Transparency v2.0 — https://www.rfc-editor.org/rfc/rfc9162.html
* Russ Cox — Transparent Logs for Skeptical Clients — https://research.swtch.com/tlog
* Issue #11 — Merkle tree upgrade to hash chain
* Closes Axiom 4 (Verifiability) — `docs/AUDITABILITY.md`
