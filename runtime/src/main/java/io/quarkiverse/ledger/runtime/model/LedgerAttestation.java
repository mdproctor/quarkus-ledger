package io.casehub.ledger.runtime.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * A peer attestation stamped onto a {@link LedgerEntry}.
 * Plain {@code @Entity} — queries via {@code @NamedQuery} + EntityManager.
 */
@Entity
@Table(name = "ledger_attestation")
@NamedQuery(name = "LedgerAttestation.findByEntryId", query = "SELECT a FROM LedgerAttestation a WHERE a.ledgerEntryId = :entryId ORDER BY a.occurredAt ASC")
@NamedQuery(name = "LedgerAttestation.findBySubjectId", query = "SELECT a FROM LedgerAttestation a WHERE a.subjectId = :subjectId ORDER BY a.occurredAt ASC")
@NamedQuery(name = "LedgerAttestation.findByEntryIds", query = "SELECT a FROM LedgerAttestation a WHERE a.ledgerEntryId IN :entryIds")
public class LedgerAttestation extends io.casehub.ledger.api.model.LedgerAttestation {

    @PrePersist
    void prePersist() {
        if (id == null)
            id = UUID.randomUUID();
        if (occurredAt == null)
            occurredAt = Instant.now();
    }
}
