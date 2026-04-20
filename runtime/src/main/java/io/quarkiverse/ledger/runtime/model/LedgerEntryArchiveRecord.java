package io.quarkiverse.ledger.runtime.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
 * {@code quarkus.ledger.retention.archive-before-delete=true} (the default).
 */
@Entity
@Table(name = "ledger_entry_archive")
public class LedgerEntryArchiveRecord {

    /** Primary key — UUID assigned on first persist. */
    @Id
    public UUID id;

    /** The {@code id} of the original {@link LedgerEntry} that was archived. */
    @Column(name = "original_entry_id", nullable = false)
    public UUID originalEntryId;

    /** The aggregate identifier of the original entry. */
    @Column(name = "subject_id", nullable = false)
    public UUID subjectId;

    /** The sequence number of the original entry within its subject chain. */
    @Column(name = "sequence_number", nullable = false)
    public int sequenceNumber;

    /**
     * Full JSON snapshot of the archived entry, including all core fields,
     * {@code supplementJson}, and all attestations. Self-contained for reconstruction.
     */
    @Column(name = "entry_json", columnDefinition = "TEXT", nullable = false)
    public String entryJson;

    /**
     * Copy of {@link LedgerEntry#occurredAt} for efficient range queries on archived
     * data without parsing {@link #entryJson}.
     */
    @Column(name = "entry_occurred_at", nullable = false)
    public Instant entryOccurredAt;

    /** When this archive record was written. */
    @Column(name = "archived_at", nullable = false)
    public Instant archivedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
