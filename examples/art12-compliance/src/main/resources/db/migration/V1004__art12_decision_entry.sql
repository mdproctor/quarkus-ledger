CREATE TABLE art12_decision_entry (
    id                UUID         NOT NULL,
    decision_category VARCHAR(100),
    CONSTRAINT pk_art12_decision_entry PRIMARY KEY (id),
    CONSTRAINT fk_art12_decision_entry FOREIGN KEY (id) REFERENCES ledger_entry (id)
);
