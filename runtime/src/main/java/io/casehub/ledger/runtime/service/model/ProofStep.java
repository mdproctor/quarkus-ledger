package io.casehub.ledger.runtime.service.model;

/**
 * One sibling hash in a Merkle inclusion proof.
 *
 * @param hash 64-char lowercase hex SHA-256
 * @param side whether the sibling is to the LEFT or RIGHT of the current node
 */
public record ProofStep(String hash, Side side) {

    /** Position of the sibling relative to the current node in the tree. */
    public enum Side {
        LEFT,
        RIGHT
    }
}
