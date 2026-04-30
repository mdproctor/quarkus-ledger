package io.casehub.ledger.runtime.service.model;

import java.util.List;
import java.util.UUID;

/**
 * A Merkle inclusion proof for a single ledger entry.
 *
 * <p>
 * Verify with: start from {@code leafHash}, apply each {@link ProofStep} as
 * {@code internalHash(step.hash, current)} for LEFT or {@code internalHash(current, step.hash)}
 * for RIGHT. The result must equal {@code treeRoot}.
 *
 * @param entryId the ledger entry this proof covers
 * @param entryIndex 0-based index of the entry in the per-subject sequence
 * @param treeSize total number of entries for this subject at proof time
 * @param leafHash SHA-256 leaf hash of this entry (stored as {@code digest} on the entry)
 * @param siblings ordered sibling hashes from leaf level to root
 * @param treeRoot Merkle root at proof time — verify against a published checkpoint
 */
public record InclusionProof(
        UUID entryId,
        int entryIndex,
        int treeSize,
        String leafHash,
        List<ProofStep> siblings,
        String treeRoot) {
}
