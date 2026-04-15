-- Quarkus Ledger base schema (V1)
-- Compatible with H2 (dev/test) and PostgreSQL (production)
--
-- ledger_entry: domain-agnostic immutable audit log.
--   subjectId    — the aggregate this entry belongs to (WorkItem UUID, Channel UUID, etc.)
--   dtype        — JPA discriminator; identifies the concrete subclass (e.g. WORK_ITEM, AGENT_MESSAGE)
--   sequence_number — per-subject ordering (1-based)
--   hash chain   — previous_hash + digest implement Certificate Transparency tamper detection
--
-- Subclass tables (e.g. work_item_ledger_entry, agent_message_ledger_entry) are defined in
-- the Flyway migrations of the consuming extension and join to this table on id.

CREATE TABLE ledger_entry (
    id                   UUID            NOT NULL,
    dtype                VARCHAR(50)     NOT NULL,
    subject_id           UUID            NOT NULL,
    sequence_number      INT             NOT NULL,
    entry_type           VARCHAR(20)     NOT NULL,
    actor_id             VARCHAR(255),
    actor_type           VARCHAR(20),
    actor_role           VARCHAR(100),
    plan_ref             VARCHAR(500),
    rationale            TEXT,
    decision_context     TEXT,
    evidence             TEXT,
    detail               TEXT,
    caused_by_entry_id   UUID,
    correlation_id       VARCHAR(255),
    source_entity_id     VARCHAR(255),
    source_entity_type   VARCHAR(255),
    source_entity_system VARCHAR(100),
    previous_hash        VARCHAR(64),
    digest               VARCHAR(64),
    occurred_at          TIMESTAMP       NOT NULL,
    CONSTRAINT pk_ledger_entry PRIMARY KEY (id)
);

CREATE INDEX idx_ledger_entry_subject_seq ON ledger_entry (subject_id, sequence_number);
CREATE INDEX idx_ledger_entry_subject_id  ON ledger_entry (subject_id);
CREATE INDEX idx_ledger_entry_correlation ON ledger_entry (correlation_id);
CREATE INDEX idx_ledger_entry_actor       ON ledger_entry (actor_id);

CREATE TABLE ledger_attestation (
    id               UUID            NOT NULL,
    ledger_entry_id  UUID            NOT NULL,
    subject_id       UUID            NOT NULL,
    attestor_id      VARCHAR(255)    NOT NULL,
    attestor_type    VARCHAR(20)     NOT NULL,
    attestor_role    VARCHAR(100),
    verdict          VARCHAR(20)     NOT NULL,
    evidence         TEXT,
    confidence       DOUBLE          NOT NULL,
    occurred_at      TIMESTAMP       NOT NULL,
    CONSTRAINT pk_ledger_attestation PRIMARY KEY (id),
    CONSTRAINT fk_attestation_entry FOREIGN KEY (ledger_entry_id) REFERENCES ledger_entry (id)
);

CREATE INDEX idx_ledger_attestation_entry   ON ledger_attestation (ledger_entry_id);
CREATE INDEX idx_ledger_attestation_subject ON ledger_attestation (subject_id);
