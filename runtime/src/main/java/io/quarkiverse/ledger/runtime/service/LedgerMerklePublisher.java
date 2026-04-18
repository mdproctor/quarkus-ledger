package io.quarkiverse.ledger.runtime.service;

import java.security.PrivateKey;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class LedgerMerklePublisher {

    public static String buildCheckpoint(UUID subjectId, int treeSize, String treeRoot, String keyId) {
        throw new UnsupportedOperationException();
    }

    public static byte[] signCheckpoint(String checkpointText, PrivateKey privateKey) {
        throw new UnsupportedOperationException();
    }

    public void publish(UUID subjectId, int treeSize, String treeRoot) {
        // no-op stub — publisher not yet implemented
    }
}
