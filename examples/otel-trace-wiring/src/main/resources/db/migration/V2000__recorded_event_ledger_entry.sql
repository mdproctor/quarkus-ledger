CREATE TABLE recorded_event_ledger_entry (
    id           UUID         NOT NULL,
    event_name   VARCHAR(200),
    CONSTRAINT pk_recorded_event_ledger_entry PRIMARY KEY (id),
    CONSTRAINT fk_recorded_event_ledger_entry FOREIGN KEY (id) REFERENCES ledger_entry(id)
);
