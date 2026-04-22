package io.quarkiverse.ledger.examples.routing.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import io.quarkiverse.ledger.runtime.model.LedgerEntry;

@Entity
@Table(name = "task_ledger_entry")
public class TaskLedgerEntry extends LedgerEntry {

    @Column(name = "task_type")
    public String taskType;
}
