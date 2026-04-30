-- OrderLedgerEntry subclass table (JPA JOINED inheritance from casehub-ledger)
-- Joins to ledger_entry(id) for all base audit fields.
-- Base tables (ledger_entry, ledger_attestation, actor_trust_score) are created
-- by casehub-ledger migrations V1000 and V1001.
CREATE TABLE IF NOT EXISTS order_ledger_entry (
    id           UUID        NOT NULL,
    order_id     UUID        NOT NULL,
    command_type VARCHAR(100),
    event_type   VARCHAR(100),
    order_status VARCHAR(50),
    CONSTRAINT pk_order_ledger_entry PRIMARY KEY (id),
    CONSTRAINT fk_order_ledger_entry FOREIGN KEY (id) REFERENCES ledger_entry (id)
);

CREATE INDEX IF NOT EXISTS idx_ole_order_id ON order_ledger_entry (order_id);
