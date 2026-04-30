package io.casehub.ledger.runtime.model;

import java.util.UUID;

import jakarta.persistence.Entity;
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
public class LedgerMerkleFrontier extends io.casehub.ledger.api.model.LedgerMerkleFrontier {

    @PrePersist
    void prePersist() {
        if (id == null)
            id = UUID.randomUUID();
    }
}
