-- Quarkus Ledger — EigenTrust actor trust score table (V1001)
-- Compatible with H2 (dev/test) and PostgreSQL (production)
--
-- actor_trust_score: nightly-computed EigenTrust-inspired reputation scores per actor.
-- actor_id is the primary key — one row per actor, upserted on each nightly run.

CREATE TABLE actor_trust_score (
    actor_id             VARCHAR(255)    NOT NULL,
    actor_type           VARCHAR(20),
    trust_score          DOUBLE          NOT NULL,
    decision_count       INT             NOT NULL,
    overturned_count     INT             NOT NULL,
    appeal_count         INT             NOT NULL,
    attestation_positive INT             NOT NULL,
    attestation_negative INT             NOT NULL,
    last_computed_at     TIMESTAMP,
    CONSTRAINT pk_actor_trust_score PRIMARY KEY (actor_id)
);
