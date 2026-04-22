CREATE TABLE task_ledger_entry (
    id            UUID         NOT NULL,
    task_type     VARCHAR(100),
    CONSTRAINT pk_task_ledger_entry PRIMARY KEY (id),
    CONSTRAINT fk_task_ledger_entry_base
        FOREIGN KEY (id) REFERENCES ledger_entry(id)
);
