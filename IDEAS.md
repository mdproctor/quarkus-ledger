# Idea Log

Undecided possibilities — things worth remembering but not yet decided.
Promote to an ADR when ready to decide; discard when no longer relevant.

---

## 2026-04-23 — Submission target: Quarkiverse vs SmallRye

**Priority:** high
**Status:** active — blocks submission

External feedback received that `quarkus-ledger` may not qualify as a true Quarkus
extension under Quarkiverse's criteria. The extension has a Quarkus deployment module
and `@BuildStep`/`FeatureBuildItem` wiring, but its core value — immutable audit log,
trust scoring, Merkle proofs — is not inherently Quarkus-specific. Any JPA/CDI runtime
could consume it.

**SmallRye** is the alternative. SmallRye hosts cross-cutting, spec-aligned libraries
that are MicroProfile/Jakarta-first rather than Quarkus-first (e.g. SmallRye Health,
SmallRye Fault Tolerance, SmallRye JWT). The ledger's SPI-based design with CDI events
and EntityManager fits that profile.

**Questions to resolve before deciding:**
- Does SmallRye accept persistence-heavy libraries (EntityManager, Flyway)?
- Does Quarkiverse explicitly require the extension to be Quarkus-specific in its value, or is the deployment module sufficient?
- Would a SmallRye submission require stripping the deployment module entirely, or can both exist?

**Parked 2026-04-23.** Quarkiverse submission deferred pending this decision.

---

## 2026-04-16 — `/auditability-check` Claude Code skill

**Priority:** medium
**Status:** active

A Claude Code slash command that applies the 8-axiom auditability framework
(ACM FAIR 2025 — Integrity, Coverage, Temporal Coherence, Verifiability,
Accessibility, Resource Proportionality, Privacy Compatibility, Governance
Alignment) to any codebase and outputs a structured `AUDITABILITY.md` gap
analysis with a priority map. Could be parameterised by compliance lens:
`8-axiom-auditability`, `article-12`, `gdpr-art-22`, `nist-ai-rmf`.

**Context:** Emerged from the quarkus-ledger research session (2026-04-16)
after producing `docs/AUDITABILITY.md`. The pattern — load a framework,
assess a codebase, apply a design constraint, output a structured gap
analysis — is general enough to offer as a cc-praxis skill alongside
`java-security-audit` and `python-security-audit`. Timely given EU AI Act
enforcement deadline of 2 August 2026.

**Promoted to:**
