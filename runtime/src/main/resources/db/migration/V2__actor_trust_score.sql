-- Actor trust scores computed nightly from ledger history (EigenTrust-inspired)
CREATE TABLE actor_trust_score (
    actor_id              VARCHAR(255) NOT NULL,
    actor_type            VARCHAR(20)  NOT NULL,
    trust_score           DOUBLE       NOT NULL DEFAULT 0.5,
    decision_count        INT          NOT NULL DEFAULT 0,
    overturned_count      INT          NOT NULL DEFAULT 0,
    appeal_count          INT          NOT NULL DEFAULT 0,
    attestation_positive  INT          NOT NULL DEFAULT 0,
    attestation_negative  INT          NOT NULL DEFAULT 0,
    last_computed_at      TIMESTAMP    NOT NULL,
    CONSTRAINT pk_actor_trust_score PRIMARY KEY (actor_id)
);

CREATE INDEX idx_actor_trust_score_type ON actor_trust_score (actor_type);
