package io.quarkiverse.ledger.runtime.model;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * One node in the Merkle Mountain Range frontier for a subject.
 *
 * <p>
 * A subject with N entries has exactly {@code Integer.bitCount(N)} rows at any time —
 * one per set bit in N's binary representation. At 1 million entries: at most 20 rows.
 */
@Entity
@Table(name = "ledger_merkle_frontier")
public class LedgerMerkleFrontier extends PanacheEntityBase {

    @Id
    public UUID id;

    /** The aggregate this frontier node belongs to. */
    @Column(name = "subject_id", nullable = false)
    public UUID subjectId;

    /** Tree level — this node is the root of a perfect subtree of 2^level leaves. */
    @Column(nullable = false)
    public int level;

    /** SHA-256 root hash of this subtree — 64-char lowercase hex. */
    @Column(nullable = false, length = 64)
    public String hash;

    @PrePersist
    void prePersist() {
        if (id == null)
            id = UUID.randomUUID();
    }

    /** Return all frontier nodes for the given subject, ordered by level ascending. */
    public static List<LedgerMerkleFrontier> findBySubjectId(final UUID subjectId) {
        return list("subjectId = ?1 ORDER BY level ASC", subjectId);
    }

    /** Delete the frontier node at the given level for the given subject, if present. */
    public static void deleteBySubjectAndLevel(final UUID subjectId, final int level) {
        delete("subjectId = ?1 AND level = ?2", subjectId, level);
    }
}
