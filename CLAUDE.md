# Quarkus Ledger — Claude Code Project Guide

## Project Type

type: java

**Stack:** Java 21 (on Java 26 JVM), Quarkus 3.32.2, GraalVM 25 (native image)

---

## What This Project Is

`quarkus-ledger` is a standalone Quarkiverse extension providing a domain-agnostic immutable
audit ledger for any Quarkus application. Any Quarkus app adds `io.quarkiverse.ledger:quarkus-ledger`
as a dependency and immediately gets:

- **Immutable append-only audit log** (`LedgerEntry` base entity with JPA JOINED inheritance)
- **Hash chain tamper evidence** (Certificate Transparency pattern — SHA-256 chaining per subject)
- **Peer attestation** (`LedgerAttestation` — verdicts, confidence scores)
- **EigenTrust reputation** (`TrustScoreComputer` — nightly batch, exponential decay weighting)
- **Provenance tracking** (`sourceEntityId / sourceEntityType / sourceEntitySystem`)
- **Decision context snapshots** (GDPR Article 22 / EU AI Act Article 12 compliance)

### Domain-Specific Subclasses

Domain logic is NOT in this extension — it lives in consumers via JPA JOINED subclasses:

| Consumer | Subclass | Subclass table | subject_id maps to |
|---|---|---|---|
| `quarkus-tarkus` | `WorkItemLedgerEntry` | `work_item_ledger_entry` | WorkItem UUID |
| `quarkus-qhorus` | `AgentMessageLedgerEntry` | `agent_message_ledger_entry` | Channel UUID |

Each consumer defines its own subclass and its own Flyway migration for the subclass table.
The base tables (`ledger_entry`, `ledger_attestation`, `actor_trust_score`) are defined here
in V1000, V1001, V1002, and V1003 and always present when `quarkus-ledger` is on the classpath.

---

## Quarkiverse Naming

| Element | Value |
|---|---|
| GitHub repo | `mdproctor/quarkus-ledger` (→ `quarkiverse/quarkus-ledger` when submitted) |
| groupId | `io.quarkiverse.ledger` |
| Parent artifactId | `quarkus-ledger-parent` |
| Runtime artifactId | `quarkus-ledger` |
| Deployment artifactId | `quarkus-ledger-deployment` |
| Root Java package | `io.quarkiverse.ledger.runtime` |
| Deployment subpackage | `io.quarkiverse.ledger.deployment` |
| Config prefix | `quarkus.ledger` |
| Feature name | `ledger` |

---

## Key Design Decisions

**`subject_id` — the generic aggregate identifier**
All queries, sequences, and hash chains are scoped per `subject_id`. This field replaces
the domain-specific `work_item_id` that was in the original Tarkus ledger. Consumers set
`subjectId` to their own aggregate UUID (WorkItem UUID, Channel UUID, etc.).

**JPA JOINED inheritance**
`LedgerEntry` is abstract with `@Inheritance(strategy = JOINED)`. Hibernate joins to all
registered subclass tables on query. `LedgerAttestation` holds a FK to the base table —
attestations work regardless of which subclass produced the entry.

**Hash chain canonical form (core fields only)**
`subjectId|seqNum|entryType|actorId|actorRole|occurredAt`
Domain-specific subclass fields and supplement fields are excluded — canonical form stays
domain-agnostic. `planRef` was moved to `ComplianceSupplement` in V1002 and removed from
the canonical form.

**`correlationId` and `causedByEntryId` are core fields**
Both OTel trace linking and causal relationships are structural — present on every entry where
relevant. They live on `LedgerEntry` directly (not in supplements). `findCausedBy(UUID entryId)`
traverses causal chains one hop at a time. The test for core vs supplement: is the field
relevant to every consumer, every entry, every time? If yes → core. If no → supplement.

**REST endpoints are domain-specific**
`quarkus-ledger` provides model, SPI, services, and JPA implementations only. Tarkus and
Qhorus each define their own REST/MCP endpoints on top.

---

## Project Structure

```
quarkus-ledger/
├── runtime/
│   └── src/main/java/io/quarkiverse/ledger/runtime/
│       ├── config/LedgerConfig.java         — @ConfigMapping(prefix = "quarkus.ledger")
│       ├── model/
│       │   ├── LedgerEntry.java             — abstract base entity (JOINED inheritance)
│       │   ├── LedgerAttestation.java       — peer attestation entity
│       │   ├── ActorTrustScore.java         — nightly-computed reputation entity
│       │   ├── LedgerEntryType.java         — COMMAND | EVENT | ATTESTATION
│       │   ├── ActorType.java               — HUMAN | AGENT | SYSTEM
│       │   └── AttestationVerdict.java      — SOUND | FLAGGED | ENDORSED | CHALLENGED
│       ├── repository/
│       │   ├── LedgerEntryRepository.java   — SPI (uses subjectId)
│       │   ├── ActorTrustScoreRepository.java — SPI
│       │   └── jpa/                         — Panache implementations
│       └── service/
│           ├── LedgerHashChain.java         — SHA-256 chain utility (pure static)
│           ├── TrustScoreComputer.java      — EigenTrust algorithm (pure Java)
│           └── TrustScoreJob.java           — @Scheduled nightly recomputation
│       └── supplement/
│           ├── LedgerSupplement.java        — abstract base (JOINED inheritance)
│           ├── ComplianceSupplement.java    — GDPR Art.22, governance fields
│           ├── ProvenanceSupplement.java    — workflow source entity
│           └── LedgerSupplementSerializer.java — JSON serialiser for supplementJson
│   └── src/main/resources/db/migration/
│       ├── V1000__ledger_base_schema.sql    — ledger_entry + ledger_attestation tables
│       ├── V1001__actor_trust_score.sql     — actor_trust_score table
│       ├── V1002__ledger_supplement.sql     — supplement tables + drops moved columns
│       └── V1003__ledger_entry_archive.sql  — ledger_entry_archive table
└── deployment/
    └── src/main/java/io/quarkiverse/ledger/deployment/
        └── LedgerProcessor.java             — @BuildStep: FeatureBuildItem
```

---

## Build and Test

```bash
# Build all modules
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install

# Run tests (runtime module)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test

# Native image build (requires GraalVM)
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home \
  mvn package -Pnative -DskipTests
```

**Use `mvn` not `./mvnw`** — maven wrapper not configured on this machine.

---

## Java and GraalVM on This Machine

```bash
# Java 26 (Oracle, system default) — use for dev and tests
JAVA_HOME=$(/usr/libexec/java_home -v 26)

# GraalVM 25 — use for native image builds only
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home
```

---

## Ecosystem Context

```
quarkus-ledger       (audit/provenance — this project)
    ↑         ↑
 tarkus    qhorus    (each adds its own LedgerEntry subclass)
    ↑         ↑
          claudony
```

Tarkus and Qhorus are siblings — neither depends on the other. Both depend on
`quarkus-ledger`. Claudony composes them.

---

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** mdproctor/quarkus-ledger

**Automatic behaviours (Claude follows these at all times in this project):**
- **Before implementation begins** — check if an active issue exists. If not, run issue-workflow Phase 1 before writing any code.
- **Before any commit** — run issue-workflow Phase 3 to confirm issue linkage.
- **All commits should reference an issue** — `Refs #N` (ongoing) or `Closes #N` (done).

---

## Writing Style Guide

**The writing style guide at `~/claude-workspace/writing-styles/blog-technical.md` is mandatory for all blog and diary entries.** Load it in full before drafting. Complete the pre-draft voice classification (I / we / Claude-named) before generating any prose. Do not show a draft without verifying it against the style guide.

**Blog directory:** `blog/`
