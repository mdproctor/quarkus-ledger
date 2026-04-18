package io.quarkiverse.ledger.runtime.service;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.ledger.runtime.service.model.InclusionProof;

@ApplicationScoped
public class LedgerVerificationService {
    public String treeRoot(UUID subjectId) {
        throw new UnsupportedOperationException();
    }

    public InclusionProof inclusionProof(UUID entryId) {
        throw new UnsupportedOperationException();
    }

    public boolean verify(UUID subjectId) {
        throw new UnsupportedOperationException();
    }
}
