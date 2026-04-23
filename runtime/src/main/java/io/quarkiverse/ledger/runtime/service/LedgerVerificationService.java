package io.quarkiverse.ledger.runtime.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkiverse.ledger.runtime.model.LedgerMerkleFrontier;
import io.quarkiverse.ledger.runtime.repository.LedgerEntryRepository;
import io.quarkiverse.ledger.runtime.service.model.InclusionProof;
import io.quarkus.hibernate.orm.PersistenceUnit;

/**
 * CDI bean exposing Merkle tree verification operations.
 * Auto-activated — no consumer configuration required.
 */
@ApplicationScoped
public class LedgerVerificationService {

    @Inject
    LedgerEntryRepository ledgerRepo;

    @Inject
    @PersistenceUnit("qhorus")
    EntityManager em;

    /** Return the current Merkle tree root for a subject. */
    @Transactional
    public String treeRoot(final UUID subjectId) {
        final List<LedgerMerkleFrontier> frontier = em
                .createNamedQuery("LedgerMerkleFrontier.findBySubjectId", LedgerMerkleFrontier.class)
                .setParameter("subjectId", subjectId)
                .getResultList();
        if (frontier.isEmpty()) {
            throw new IllegalStateException("No entries for subject " + subjectId);
        }
        return LedgerMerkleTree.treeRoot(frontier);
    }

    /**
     * Generate an inclusion proof for the given entry.
     * Fetches all leaf hashes for the subject from the database (ordered by sequenceNumber).
     * The returned proof carries the authoritative root from the stored frontier.
     */
    @Transactional
    public InclusionProof inclusionProof(final UUID entryId) {
        final LedgerEntry entry = ledgerRepo.findEntryById(entryId).orElse(null);
        if (entry == null)
            throw new IllegalArgumentException("Entry not found: " + entryId);

        final List<LedgerEntry> allForSubject = ledgerRepo.findBySubjectId(entry.subjectId);

        final List<String> leafHashes = allForSubject.stream()
                .map(e -> e.digest)
                .toList();

        final int k = entry.sequenceNumber - 1;
        final String root = treeRoot(entry.subjectId); // authoritative root from frontier
        final InclusionProof proof = LedgerMerkleTree.inclusionProof(
                entryId, k, leafHashes.size(), leafHashes);

        return new InclusionProof(entryId, k, leafHashes.size(),
                proof.leafHash(), proof.siblings(), root);
    }

    /**
     * Verify that all stored digests are consistent with recomputed leaf hashes.
     * Returns false if any entry's stored digest doesn't match its canonical hash.
     */
    @Transactional
    public boolean verify(final UUID subjectId) {
        final List<LedgerEntry> entries = ledgerRepo.findBySubjectId(subjectId);

        List<LedgerMerkleFrontier> frontier = new ArrayList<>();
        for (final LedgerEntry entry : entries) {
            final String expected = LedgerMerkleTree.leafHash(entry);
            if (!expected.equals(entry.digest))
                return false;
            frontier = LedgerMerkleTree.append(expected, frontier, subjectId);
        }

        if (frontier.isEmpty())
            return true;

        final String computed = LedgerMerkleTree.treeRoot(frontier);
        final String stored = treeRoot(subjectId);
        return computed.equals(stored);
    }
}
