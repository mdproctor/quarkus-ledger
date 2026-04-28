-- Quarkus Ledger — actor trust score table (V1001)
-- Compatible with H2 2.4.240+ (dev/test) and PostgreSQL 15+ (production)
--
-- actor_trust_score: nightly-computed trust scores per actor and score type.
--
-- score_type:  GLOBAL     — one row per actor; classic Bayesian Beta score across all decisions
--              CAPABILITY — one row per (actor, capability tag); scoped Beta score (#61)
--              DIMENSION  — one row per (actor, trust dimension); e.g. thoroughness (#62)
--
-- scope_key:   NULL for GLOBAL rows; capability tag string for CAPABILITY; dimension name for DIMENSION.
--
-- Uniqueness: UNIQUE NULLS NOT DISTINCT (actor_id, score_type, scope_key)
--   NULLs are treated as equal for this constraint (PostgreSQL 15+ / H2 2.4.240+), so
--   two GLOBAL rows for the same actor correctly produce a constraint violation.
--
-- trust_score:        Bayesian Beta direct score: alpha_value / (alpha_value + beta_value)
-- global_trust_score: EigenTrust eigenvector component; values sum to ≤ 1.0 across all actors.
--                     Zero when EigenTrust is disabled or not yet computed (GLOBAL rows only).

CREATE TABLE actor_trust_score (
    id                   UUID             NOT NULL,
    actor_id             VARCHAR(255)     NOT NULL,
    score_type           VARCHAR(20)      NOT NULL DEFAULT 'GLOBAL',
    scope_key            VARCHAR(255),
    actor_type           VARCHAR(20),          -- Nullable: populated by TrustScoreJob on first upsert; may be absent for actors with no EVENT entries
    trust_score          DOUBLE PRECISION NOT NULL,
    global_trust_score   DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    alpha_value          DOUBLE PRECISION NOT NULL,
    beta_value           DOUBLE PRECISION NOT NULL,
    decision_count       INT              NOT NULL,
    overturned_count     INT              NOT NULL,
    attestation_positive INT              NOT NULL,
    attestation_negative INT              NOT NULL,
    last_computed_at     TIMESTAMP,
    CONSTRAINT pk_actor_trust_score PRIMARY KEY (id),
    CONSTRAINT uq_actor_trust_score_key UNIQUE NULLS NOT DISTINCT (actor_id, score_type, scope_key)
);
