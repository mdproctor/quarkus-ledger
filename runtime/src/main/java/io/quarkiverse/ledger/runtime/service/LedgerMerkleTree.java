package io.quarkiverse.ledger.runtime.service;

import java.util.List;
import java.util.UUID;

import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkiverse.ledger.runtime.model.LedgerMerkleFrontier;
import io.quarkiverse.ledger.runtime.service.model.InclusionProof;

public final class LedgerMerkleTree {
    private LedgerMerkleTree() {
    }

    public static String leafHash(LedgerEntry entry) {
        throw new UnsupportedOperationException();
    }

    public static String internalHash(String leftHex, String rightHex) {
        throw new UnsupportedOperationException();
    }

    public static List<LedgerMerkleFrontier> append(String leafHash, List<LedgerMerkleFrontier> frontier, UUID subjectId) {
        throw new UnsupportedOperationException();
    }

    public static String treeRoot(List<LedgerMerkleFrontier> frontier) {
        throw new UnsupportedOperationException();
    }

    public static InclusionProof inclusionProof(UUID entryId, int entryIndex, int treeSize, List<String> leafHashes) {
        throw new UnsupportedOperationException();
    }

    public static boolean verifyProof(InclusionProof proof, String expectedRoot) {
        throw new UnsupportedOperationException();
    }
}
