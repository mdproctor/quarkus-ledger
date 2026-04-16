-- Test-only migration: concrete subclass table for LedgerSupplementIT
CREATE TABLE test_ledger_entry (
    id UUID NOT NULL,
    CONSTRAINT pk_test_ledger_entry PRIMARY KEY (id),
    CONSTRAINT fk_test_entry FOREIGN KEY (id) REFERENCES ledger_entry (id)
);
