CREATE TABLE document_classification_ledger_entry (
    id               UUID         NOT NULL,
    document_id      UUID         NOT NULL,
    risk_level       VARCHAR(20),
    CONSTRAINT pk_doc_classification_ledger_entry PRIMARY KEY (id),
    CONSTRAINT fk_doc_classification_ledger_entry FOREIGN KEY (id) REFERENCES ledger_entry(id)
);
