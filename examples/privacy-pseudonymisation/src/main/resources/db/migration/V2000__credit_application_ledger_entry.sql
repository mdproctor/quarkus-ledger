CREATE TABLE credit_application_ledger_entry (
    id               UUID         NOT NULL,
    application_id   UUID         NOT NULL,
    decision_type    VARCHAR(100),
    outcome          VARCHAR(50),
    CONSTRAINT pk_credit_application_ledger_entry PRIMARY KEY (id),
    CONSTRAINT fk_credit_application_ledger_entry FOREIGN KEY (id) REFERENCES ledger_entry(id)
);
