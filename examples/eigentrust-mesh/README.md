# EigenTrust Mesh Example

## What is EigenTrust?

EigenTrust computes *global* trust scores by propagating trust transitively through a peer
attestation network. Where the Bayesian Beta model gives each agent a `trustScore` based only
on its own direct attestation history, EigenTrust treats the attestation graph as a matrix and
runs power iteration to find an eigenvector — a stable distribution of global trust across all
agents. If agent A trusts agent B and agent B trusts agent C, EigenTrust gives A a derived
signal about C even without a direct A-to-C attestation.

## The Problem it Solves

In large agent meshes, most agents never directly attest on each other's decisions. The
attestation graph is sparse. A pure direct-trust system is then unreliable: agents that happen
to work on the same tasks accumulate history; agents that work on different tasks share none.
EigenTrust fills the sparse edges using transitive inference, so the reputation of the overall
network informs individual agent scores even when direct observations are limited.

## What this Example Shows

Three AI agents classify documents as LOW/MEDIUM/HIGH risk. After each classification, the
other two agents peer-review the result and submit a `LedgerAttestation` with their verdict
(SOUND, ENDORSED, FLAGGED, or CHALLENGED).

| Agent | Direct signal | Role in the mesh |
|---|---|---|
| `classifier-a` | SOUND from b and c | Reliable — highest direct trust |
| `classifier-b` | SOUND from c, CHALLENGED from a | Moderate — partially trusted |
| `classifier-c` | FLAGGED from a and b | Unreliable — lowest direct trust |

After running `TrustScoreJob.runComputation()`:

- **`trustScore`** — Bayesian Beta from direct attestations only. Correctly orders a > b > c.
- **`globalTrustScore`** — EigenTrust eigenvector share. Propagates through the mesh; a's high
  incoming trust (from b and c) amplifies its global score, while c's negative incoming trust
  depresses it further even for agents that have not directly attested on c.

## Running the Tests

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -f examples/eigentrust-mesh/pom.xml
```

## REST API

```
POST /mesh/seed     seed document classifications and peer attestations
POST /mesh/compute  run TrustScoreJob (Bayesian Beta + EigenTrust)
GET  /mesh/scores   return [{actorId, trustScore, globalTrustScore, decisionCount}]
```
