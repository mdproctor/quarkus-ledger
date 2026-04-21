-- Quarkus Ledger — actor identity pseudonymisation table (V1004)
-- Compatible with H2 (dev/test) and PostgreSQL (production)
--
-- actor_identity: optional token-to-identity mapping for pseudonymisation.
-- Always created. Empty when quarkus.ledger.identity.tokenisation.enabled=false.
-- UNIQUE (actor_id) ensures one stable token per rawActorId.
-- Erasure deletes the row; stored tokens become permanently unresolvable.

CREATE TABLE actor_identity (
    token      VARCHAR(255) NOT NULL,
    actor_id   VARCHAR(255) NOT NULL,
    created_at TIMESTAMP    NOT NULL,
    CONSTRAINT pk_actor_identity PRIMARY KEY (token),
    CONSTRAINT uq_actor_identity_actor_id UNIQUE (actor_id)
);
