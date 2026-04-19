CREATE TABLE prov_example_entry (
    id UUID NOT NULL,
    CONSTRAINT pk_prov_example_entry PRIMARY KEY (id),
    CONSTRAINT fk_prov_example_entry FOREIGN KEY (id) REFERENCES ledger_entry (id)
);
