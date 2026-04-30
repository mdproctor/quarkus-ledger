package io.casehub.ledger.runtime.model;

import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Persisted archive record written by the retention job before a
 * {@link LedgerEntry} is removed from the main table.
 *
 * <p>
 * {@link #entryJson} contains a complete, self-contained JSON snapshot of the original
 * entry — all core fields, {@code supplementJson}, and all attestations — sufficient
 * for full reconstruction without access to the original tables.
 *
 * <p>
 * Archive records are written only when
 * {@code casehub.ledger.retention.archive-before-delete=true} (the default).
 */
@Entity
@Table(name = "ledger_entry_archive")
public class LedgerEntryArchiveRecord extends io.casehub.ledger.api.model.LedgerEntryArchiveRecord {

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
