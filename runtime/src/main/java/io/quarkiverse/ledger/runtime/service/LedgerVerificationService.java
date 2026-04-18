package io.quarkiverse.ledger.runtime.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkiverse.ledger.runtime.model.LedgerMerkleFrontier;
import io.quarkiverse.ledger.runtime.service.model.InclusionProof;

/**
 * CDI bean exposing Merkle tree verification operations.
 * Auto-activated — no consumer configuration required.
 */
@ApplicationScoped
public class LedgerVerificationService {

    /** Return the current Merkle tree root for a subject. */
    @Transactional
    public String treeRoot(final UUID subjectId) {
        final List<LedgerMerkleFrontier> frontier = LedgerMerkleFrontier.findBySubjectId(subjectId);
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
        final LedgerEntry entry = LedgerEntry.<LedgerEntry> list("id = ?1", entryId).stream()
                .findFirst().orElse(null);
        if (entry == null)
            throw new IllegalArgumentException("Entry not found: " + entryId);

        final List<LedgerEntry> allForSubject = LedgerEntry.<LedgerEntry> list(
                "subjectId = ?1 ORDER BY sequenceNumber ASC", entry.subjectId);

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
        final List<LedgerEntry> entries = LedgerEntry.<LedgerEntry> list(
                "subjectId = ?1 ORDER BY sequenceNumber ASC", subjectId);

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
