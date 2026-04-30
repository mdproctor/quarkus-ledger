package io.casehub.ledger.api.model;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

/**
 * One node in the Merkle Mountain Range frontier for a subject.
 *
 * <p>
 * A subject with N entries has exactly {@code Integer.bitCount(N)} rows at any time.
 */
@MappedSuperclass
public class LedgerMerkleFrontier {

    @Id
    public UUID id;

    @Column(name = "subject_id", nullable = false)
    public UUID subjectId;

    @Column(nullable = false)
    public int level;

    @Column(nullable = false, length = 64)
    public String hash;
}
