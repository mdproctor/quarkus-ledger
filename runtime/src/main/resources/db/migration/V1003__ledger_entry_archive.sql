-- V1003 — Archive table for entries past their retention window.
--
-- When quarkus.ledger.retention.enabled=true and archive-before-delete=true,
-- the retention job writes a full JSON snapshot of each entry here before
-- deleting it from ledger_entry. The archive is self-contained: entry_json
-- includes all core fields, supplementJson, and all attestations.
--
-- entry_occurred_at is a copy of LedgerEntry.occurredAt stored on the archive
-- row to allow efficient range queries on archived data without parsing entry_json.
--
-- Compatible with H2 (dev/test) and PostgreSQL (production).

CREATE TABLE ledger_entry_archive (
    id                UUID        NOT NULL,
    original_entry_id UUID        NOT NULL,
    subject_id        UUID        NOT NULL,
    sequence_number   INT         NOT NULL,
    entry_json        TEXT        NOT NULL,
    entry_occurred_at TIMESTAMP   NOT NULL,
    archived_at       TIMESTAMP   NOT NULL,
    CONSTRAINT pk_ledger_entry_archive PRIMARY KEY (id)
);

CREATE INDEX idx_archive_subject  ON ledger_entry_archive (subject_id);
CREATE INDEX idx_archive_occurred ON ledger_entry_archive (entry_occurred_at);
