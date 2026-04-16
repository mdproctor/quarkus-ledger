CREATE TABLE decision_ledger_entry (
    id                UUID         NOT NULL,
    decision_id       UUID         NOT NULL,
    decision_category VARCHAR(100),
    outcome           VARCHAR(50),
    CONSTRAINT pk_decision_ledger_entry PRIMARY KEY (id),
    CONSTRAINT fk_decision_entry FOREIGN KEY (id) REFERENCES ledger_entry (id)
);
