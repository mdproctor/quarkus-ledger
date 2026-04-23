# Retro Issues — quarkus-ledger

Generated: 2026-04-22. Covers all 181 commits from 2026-04-15 to 2026-04-22.

---

## Summary

The repository already has comprehensive issue coverage across #1–#38. All
substantive feature, test, and compliance work is tracked. The unlinked commits
are CLAUDE.md maintenance, implementation plan docs, RESEARCH.md updates, and
planning artifacts — these map to their parent issues and do not warrant new issues.

**Action required:**
- Close #36 (example modules fix — done)
- Close #37 (docs drift / integration guide — done)
- No new issues needed

---

## Epic Map

| Epic | Issues | Period | Status |
|---|---|---|---|
| #1 Initial extension scaffold | standalone | 2026-04-15 | ✅ Closed |
| #6 Compliance and trust quality | #7 GDPR Art.22, #8 Forgiveness, #9 EU AI Act Art.12, #10 Causality | 2026-04-16–17 | ✅ Closed |
| #12 Verifiability — Merkle upgrade | #11 Merkle MMR upgrade | 2026-04-17–18 | ✅ Closed |
| #13 W3C PROV-DM export | #14 PROV-DM implementation | 2026-04-18 | ✅ Closed |
| #15 Reactive support | #16 LedgerEntry plain @Entity, #17 docs fix, #18 Panache removal epic | 2026-04-19–20 | ✅ Closed |
| #22 LLM agent mesh trust | #23 identity model, #24 continuity, #25 versioning, #26 EigenTrust, #27 topology | 2026-04-20–21 | ✅ Closed |
| #32 Trust score routing signals | #33 publisher implementation, #34 E2E example, #35 DESIGN staleness | 2026-04-22 | ✅ Closed |

---

## Open Issues to Close

### #36 Fix example modules to compile against current runtime SPI

**Work done** (2026-04-22):
- `4c84d3c` fix: update example modules to extend JpaLedgerEntryRepository and fix API drift
- `4c3bf04` docs: fix LedgerEntryRepository Javadoc — extension uses EntityManager not Panache

**Action:** Close #36

### #37 Project health — documentation drift, integration guide improvements, example compilation

**Work done** (2026-04-22):
- `f0e2c8f` docs: fix correlationId→traceId drift, Step 4 repository pattern, README gaps
- `05be0c7` docs: @Alternative activation guide — all three paths with decision table
- `6377495` docs: fix DESIGN.md and RESEARCH.md staleness
- `252f18a` refactor: extract seedDecision fixture, rename traceId in API, document jandex pin
- `66a70d0` refactor: project-refine A/B/C improvements (Closes #38)

**Action:** Close #37

---

## Unlinked Commits → Parent Issue Mapping

These commits have no issue reference but map cleanly to existing closed issues.
No new issues needed.

| Commit | Subject | Maps to |
|---|---|---|
| `05be0c7` | docs: @Alternative activation guide | #37 |
| `7b21c27` | docs: implementation plan for trust score routing signals | #32 (planning artifact) |
| `d17934c` | docs: design spec for trust score routing signals | #32 (planning artifact) |
| `d678972` | docs: privacy/pseudonymisation implementation plan | #29 (planning artifact) |
| `3d02392` | docs: update CLAUDE.md — privacy package, V1004, Bayesian Beta | #29 |
| `c682da5` | fix(test): isolate TrustScoreIT datasource (PK collisions) | #28 |
| `177f746` | docs: update RESEARCH.md — mark items 1–5 done | session wrap artifact |
| `90e6264` | docs: update CLAUDE.md — plain @Entity, @NamedQuery | #18–#21 |
| `42f3bb2` | docs: update CLAUDE.md — plain @Entity, findEntryById rename | #16–#21 |
| `26f7344` | docs: fix integration-guide — remove deleted LedgerHashChain API | #17 |
| `ca0472d` | docs: fix DESIGN.md — remove stale references, add PROV-DM section | #13/17 |
| `137ef45` | docs: update CLAUDE.md and RESEARCH.md — Merkle Mountain Range | #11/12 |
| `ee15e59` | docs: add PROV-DM export implementation plan | #13 (planning artifact) |
| `430598` | fix(examples): migrate from LedgerHashChain to LedgerMerkleTree | #11/12 |
| `b65668` | docs: update CLAUDE.md — remove ObservabilitySupplement | supplement refactor |
| `357379` | docs: revise causality plan — no migration ceremony, 3 tasks | #10 (planning artifact) |
| `01af7f` | chore: delete SQL migration scripts | schema cleanup |
| `b5a9dd` | docs: simplify README | #4 |
| `f5f4fc` | docs: log idea — /auditability-check Claude Code skill | IDEAS.md artifact |
| `2da3c6` | docs: research directions and auditability axiom gap analysis | #6 (research) |
| `f0e812` | docs: DESIGN.md — permanent architecture, decisions, roadmap | #1 |
| `fb46e9` | docs: add Flyway ordering warning | #4 |
| `3c91a0` | fix: register LedgerConfig with @ConfigRoot | #1/2 |
| `6bc0b92` | fix: detach currentScores before routing publish | #33 |
| `d808377` | fix: clarify CDI fire()/fireAsync() routing | #33 |
| `6377495` | docs: fix DESIGN.md and RESEARCH.md staleness | #35 |

---

## Excluded Commits (no ticket warranted)

| Commit | Reason |
|---|---|
| All `docs: session handover/wrap` commits | Session artifacts — not project deliverables |
| All `docs: add blog entry` commits | Narrative artifacts — not project deliverables |
| `025d513` chore: add .worktrees/ to .gitignore | Pure gitignore maintenance |
| `72117d5` chore: expand Work Tracking in CLAUDE.md | Issue workflow setup artifact |
| All implementation plan / design spec docs | Planning artifacts for their parent issues |

---

## Actions (awaiting YES)

1. Close #36 with retrospective note
2. Close #37 with retrospective note
3. No new issues to create — coverage is complete
