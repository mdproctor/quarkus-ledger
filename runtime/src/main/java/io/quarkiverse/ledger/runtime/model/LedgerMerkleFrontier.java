package io.quarkiverse.ledger.runtime.model;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * One node in the Merkle Mountain Range frontier for a subject.
 *
 * <p>
 * A subject with N entries has exactly {@code Integer.bitCount(N)} rows at any time.
 * Plain {@code @Entity} — queries via {@code @NamedQuery} + EntityManager.
 */
@Entity
@Table(name = "ledger_merkle_frontier")
@NamedQuery(name = "LedgerMerkleFrontier.findBySubjectId", query = "SELECT f FROM LedgerMerkleFrontier f WHERE f.subjectId = :subjectId ORDER BY f.level ASC")
@NamedQuery(name = "LedgerMerkleFrontier.deleteBySubjectAndLevel", query = "DELETE FROM LedgerMerkleFrontier f WHERE f.subjectId = :subjectId AND f.level = :level")
public class LedgerMerkleFrontier {

    @Id
    public UUID id;

    @Column(name = "subject_id", nullable = false)
    public UUID subjectId;

    @Column(nullable = false)
    public int level;

    @Column(nullable = false, length = 64)
    public String hash;

    @PrePersist
    void prePersist() {
        if (id == null)
            id = UUID.randomUUID();
    }
}
