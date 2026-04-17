-- V1002 — LedgerSupplement architecture
--
-- 1. Remove optional fields migrated to supplements from ledger_entry.
-- 2. Add supplement_json column for fast denormalised reads.
-- 3. Create ledger_supplement base table and three joined subclass tables.
--
-- Compatible with H2 (dev/test) and PostgreSQL (production).
-- No data migration required — all consumers are pre-release (v1.0.0-SNAPSHOT).
--
-- Flyway convention update: base extension reserves V1000–V1002.
-- Consumer subclass join tables must use V1003+ (updated from the previous V1002+).

-- ── Alter ledger_entry ───────────────────────────────────────────────────────

ALTER TABLE ledger_entry DROP COLUMN IF EXISTS plan_ref;
ALTER TABLE ledger_entry DROP COLUMN IF EXISTS rationale;
ALTER TABLE ledger_entry DROP COLUMN IF EXISTS evidence;
ALTER TABLE ledger_entry DROP COLUMN IF EXISTS detail;
ALTER TABLE ledger_entry DROP COLUMN IF EXISTS decision_context;
ALTER TABLE ledger_entry DROP COLUMN IF EXISTS correlation_id;
ALTER TABLE ledger_entry DROP COLUMN IF EXISTS caused_by_entry_id;
ALTER TABLE ledger_entry DROP COLUMN IF EXISTS source_entity_id;
ALTER TABLE ledger_entry DROP COLUMN IF EXISTS source_entity_type;
ALTER TABLE ledger_entry DROP COLUMN IF EXISTS source_entity_system;

ALTER TABLE ledger_entry ADD COLUMN supplement_json TEXT;

-- ── ledger_supplement base table ─────────────────────────────────────────────

CREATE TABLE ledger_supplement (
    id               UUID         NOT NULL,
    ledger_entry_id  UUID         NOT NULL,
    supplement_type  VARCHAR(30)  NOT NULL,
    CONSTRAINT pk_ledger_supplement PRIMARY KEY (id),
    CONSTRAINT fk_supplement_entry FOREIGN KEY (ledger_entry_id)
        REFERENCES ledger_entry (id)
);

CREATE INDEX idx_ledger_supplement_entry ON ledger_supplement (ledger_entry_id);
CREATE INDEX idx_ledger_supplement_type  ON ledger_supplement (supplement_type);

-- ── ComplianceSupplement ──────────────────────────────────────────────────────

CREATE TABLE ledger_supplement_compliance (
    id                       UUID          NOT NULL,
    -- Governance
    plan_ref                 VARCHAR(500),
    rationale                TEXT,
    evidence                 TEXT,
    detail                   TEXT,
    -- GDPR Art.22 / EU AI Act Art.12
    decision_context         TEXT,
    algorithm_ref            VARCHAR(500),
    confidence_score         DOUBLE,
    contestation_uri         VARCHAR(2000),
    human_override_available BOOLEAN,
    CONSTRAINT pk_ledger_supplement_compliance PRIMARY KEY (id),
    CONSTRAINT fk_compliance_base FOREIGN KEY (id)
        REFERENCES ledger_supplement (id)
);

-- ── ProvenanceSupplement ──────────────────────────────────────────────────────

CREATE TABLE ledger_supplement_provenance (
    id                   UUID          NOT NULL,
    source_entity_id     VARCHAR(255),
    source_entity_type   VARCHAR(255),
    source_entity_system VARCHAR(100),
    CONSTRAINT pk_ledger_supplement_provenance PRIMARY KEY (id),
    CONSTRAINT fk_provenance_base FOREIGN KEY (id)
        REFERENCES ledger_supplement (id)
);

-- ── ObservabilitySupplement ───────────────────────────────────────────────────

CREATE TABLE ledger_supplement_observability (
    id                 UUID          NOT NULL,
    correlation_id     VARCHAR(255),
    caused_by_entry_id UUID,
    CONSTRAINT pk_ledger_supplement_observability PRIMARY KEY (id),
    CONSTRAINT fk_observability_base FOREIGN KEY (id)
        REFERENCES ledger_supplement (id)
);
