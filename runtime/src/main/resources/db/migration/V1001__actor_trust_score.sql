-- Quarkus Ledger — actor trust score table (V1001)
-- Compatible with H2 (dev/test) and PostgreSQL (production)
--
-- actor_trust_score: nightly-computed trust scores per actor.
-- actor_id is the primary key — one row per actor, upserted on each nightly run.
--
-- trust_score:        Bayesian Beta direct score; alpha_value / (alpha_value + beta_value).
-- global_trust_score: EigenTrust eigenvector component; values sum to ≤ 1.0 across all actors.
--                     Zero when EigenTrust is disabled or not yet computed.

CREATE TABLE actor_trust_score (
    actor_id             VARCHAR(255)    NOT NULL,
    actor_type           VARCHAR(20),
    trust_score          DOUBLE PRECISION          NOT NULL,
    global_trust_score   DOUBLE PRECISION          NOT NULL DEFAULT 0.0,
    alpha_value          DOUBLE PRECISION          NOT NULL,
    beta_value           DOUBLE PRECISION          NOT NULL,
    decision_count       INT             NOT NULL,
    overturned_count     INT             NOT NULL,
    attestation_positive INT             NOT NULL,
    attestation_negative INT             NOT NULL,
    last_computed_at     TIMESTAMP,
    CONSTRAINT pk_actor_trust_score PRIMARY KEY (actor_id)
);
