# Quarkus Ledger — Research & Strategic Directions

Research conducted 2026-04-16. Sources: web search + Google Scholar sweep across audit
ledger cryptography, trust algorithms, EU/GDPR compliance, AI accountability, and
distributed systems causality literature.

---

## Priority Matrix

Effort: **S** = days · **M** = 1–2 weeks · **L** = 3–4 weeks+

Impact scored specifically for **agentic-AI collaboration** — trust between agents,
auditability of AI decisions, regulatory compliance.

| # | Direction | Effort | Impact | Priority | Rationale |
|---|---|---|---|---|---|
| 1 | **GDPR Art. 22 decision snapshot enrichment** | S | High | ★★★★★ | Fields already exist (`planRef`, decision context). Adding structured inputs / algorithm-ref / confidence / contestation-link is a model change only. Directly used by every AI decision in Tarkus/Qhorus today. |
| 2 | **Auditability axioms self-assessment** | S | High | ★★★★★ | Zero code — apply the 8-axiom framework as a gap analysis. Outputs a concrete backlog. Likely surfaces the other items below as findings. Do this first or in parallel with #1. |
| 3 | **Forgiveness mechanism in trust scoring** | S | High | ★★★★ | AI agents fail transiently (network timeouts, model errors) — pure EigenTrust punishes them permanently. A severity + frequency + recency forgiveness factor is a small change to `TrustScoreComputer` with outsized fairness impact. |
| 4 | **EU AI Act Article 12 compliance surface** | M | High | ★★★★ | Hard external deadline: **2 August 2026** (15 months). The ledger is already the right tool — it just needs retention config (`quarkus.ledger.retention.*`), a completeness-verification endpoint, and explicit compliance documentation. Non-compliance penalty up to €15M / 3% global turnover. |
| 5 | **Causality field (Lamport, not full vector clocks)** | M | High | ★★★★ | When Claudony orchestrates Tarkus + Qhorus, entries across systems that share a causal trigger have no way to express that today. A single `causedBy: UUID` field costs one column + one FK but enables full cross-system causal chain reconstruction. |
| 6 | **Time-weighted Bayesian trust** | ~~M~~ | ~~Medium~~ | ✅ Done | Bayesian Beta model: per-attestation α/β accumulation with recency weighting. `ForgivenessParams` removed. See ADR 0003. |
| 7 | **W3C PROV-DM JSON-LD export** | ~~M~~ | ~~Medium~~ | ✅ Done | `LedgerProvSerializer` + `LedgerProvExportService` shipped. See `docs/prov-dm-mapping.md`. |
| 8 | **Merkle tree upgrade to hash chain** | ~~L~~ | ~~Medium~~ | ✅ Done | `LedgerMerkleTree` (RFC 9162 MMR), `LedgerVerificationService`, `LedgerMerklePublisher`. ADR 0002. |
| 9 | **EERP reputation chains (per-interaction history)** | L | Medium | ★★ | Full interaction history per actor enables collusion detection. Significant data model change (new table, query patterns, index strategy). Valuable in large multi-agent deployments with adversarial actors; overkill for the current 3-consumer ecosystem. |
| 10 | **Zero-knowledge proofs** | XL | Low (now) | ★ | Technically compelling but JVM tooling is immature, complexity is extreme, and no current consumer has a privacy-vs-auditability conflict that requires it. Monitor the space, build later. |

---

## The Clear Top Four (Active Sprint)

| # | Feature | Why Now |
|---|---|---|
| 2 | Auditability axiom gap analysis | Costs nothing; scopes all other work |
| 1 | GDPR Art. 22 enrichment | Small, immediately valuable to Tarkus/Qhorus |
| 3 | Forgiveness in trust scoring | Protects AI agents from transient-failure reputation damage |
| 4 | EU AI Act Article 12 plumbing | Clock is running — enforcement August 2026 |

Items 5–7 are the natural next milestone after these land.

**Note:** Items 7 (PROV-DM export) and 8 (Merkle tree upgrade) have been completed. See Priority Matrix above.

---

## Research Sources

### Hash Chain / Merkle Tree / Tamper Evidence

- [AuditableLLM: Hash-Chain-Backed Audit Framework for LLMs (MDPI 2025)](https://www.mdpi.com/2079-9292/15/1/56)
- [Recursive Transaction Hash Chains for Mortgage Platforms (IJETCSIT 2025)](https://ijetcsit.org/index.php/ijetcsit/article/view/481)
- [Trillian — open-source tamper-evident log](https://transparency.dev/)
- [RFC 6962: Certificate Transparency](https://www.rfc-editor.org/rfc/rfc6962.html)
- [RFC 9162: Certificate Transparency v2.0](https://www.rfc-editor.org/rfc/rfc9162.html)
- [Sunlight: tile-based CT log (FiloSottile)](https://github.com/FiloSottile/sunlight)
- [Transparent Logs for Skeptical Clients — Russ Cox](https://research.swtch.com/tlog)
- [Merkle tree-based logging — Register Dynamics](https://www.register-dynamics.co.uk/data-trusts/merkle-trees)

### Trust & Reputation Algorithms

- [EigenTrust — original Stanford paper](https://nlp.stanford.edu/pubs/eigentrust.pdf)
- [EERP: Enhanced EigenTrust with Reputation Chains (ScienceDirect)](https://www.sciencedirect.com/science/article/pii/S1877050918317861)
- [HonestPeer: Dynamic Pre-Trusted Peer Sets (ScienceDirect)](https://www.sciencedirect.com/science/article/pii/S1319157815000440)
- [OpenRank — EigenTrust in modern open-source ecosystems](https://docs.openrank.com/reputation-algorithms/eigentrust)
- [Bayesian Adaptive Probabilistic Trust Management (Scientific Reports 2025)](https://www.nature.com/articles/s41598-025-92643-z)

### EU AI Act / GDPR

- [EU AI Act Article 12 — official text](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-12/)
- [Article 12 compliance checklist (ISMS.online)](https://www.isms.online/iso-42001/eu-ai-act/article-12/)
- [EU AI Act audit trail for AI-generated code (CodeSlick 2026)](https://codeslick.dev/blog/eu-ai-act-audit-trail-2026)
- [GDPR Article 22 — automated decision-making text](https://gdpr-info.eu/art-22-gdpr/)
- [ICO guidance on Art. 22 and automated decisions](https://ico.org.uk/for-organisations/uk-gdpr-guidance-and-resources/individual-rights/individual-rights/rights-related-to-automated-decision-making-including-profiling/)

### AI Agent Accountability & Auditability

- [Creating Characteristically Auditable Agentic AI Systems (ACM FAIR 2025)](https://dl.acm.org/doi/10.1145/3759355.3759356)
- [Visibility into AI Agents (ACM FAccT 2024)](https://dl.acm.org/doi/10.1145/3630106.3658948)
- [2025 AI Agent Index (MIT)](https://aiagentindex.mit.edu/data/2025-AI-Agent-Index.pdf)
- [TRiSM for Agentic AI — Trust, Risk, Security Management (ScienceDirect 2025)](https://www.sciencedirect.com/article/pii/S2666651026000069)

### Provenance Standards

- [W3C PROV-DM: The Provenance Data Model](https://www.w3.org/TR/prov-dm/)
- [W3C PROV Overview](https://www.w3.org/TR/prov-overview/)

### Causality & Distributed Ordering

- [Vector Clocks — Wikipedia](https://en.wikipedia.org/wiki/Vector_clock)
- [Clocks and Causality — Ordering Events in Distributed Systems](https://www.exhypothesi.com/clocks-and-causality/)

### Zero-Knowledge Proofs

- [ZK Compliance — Privacy-Preserving Verification (Security Boulevard 2026)](https://securityboulevard.com/2026/01/zero-knowledge-compliance-how-privacy-preserving-verification-is-transforming-regulatory-technology/)
- [Privacy-Preserving Non-interactive Compliance Audits with ZKPs (ResearchGate)](https://www.researchgate.net/publication/383517250_Privacy-Preserving_Noninteractive_Compliance_Audits_of_Blockchain_Ledgers_with_Zero-Knowledge_Proofs)
