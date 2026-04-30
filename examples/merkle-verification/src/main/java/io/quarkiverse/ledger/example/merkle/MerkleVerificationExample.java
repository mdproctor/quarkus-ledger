package io.casehub.ledger.example.merkle;

import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.casehub.ledger.runtime.model.ActorType;
import io.casehub.ledger.runtime.model.LedgerEntry;
import io.casehub.ledger.runtime.model.LedgerEntryType;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;
import io.casehub.ledger.runtime.service.LedgerMerkleTree;
import io.casehub.ledger.runtime.service.LedgerVerificationService;
import io.casehub.ledger.runtime.service.model.InclusionProof;

/**
 * Demonstrates Merkle tree inclusion proof generation and independent verification.
 *
 * <p>Happy path: write 5 entries → get tree root → generate proof → verify without DB access.
 */
@ApplicationScoped
public class MerkleVerificationExample {

    @Inject LedgerEntryRepository repo;
    @Inject LedgerVerificationService verification;

    @Transactional
    public VerificationResult runHappyPath(final UUID subjectId) {
        for (int i = 1; i <= 5; i++) {
            final AuditEntry e = new AuditEntry();
            e.subjectId = subjectId;
            e.sequenceNumber = i;
            e.entryType = LedgerEntryType.EVENT;
            e.actorId = "example-actor";
            e.actorType = ActorType.SYSTEM;
            e.actorRole = "Demonstrator";
            repo.save(e);
        }

        final String root = verification.treeRoot(subjectId);

        final List<LedgerEntry> entries = repo.findBySubjectId(subjectId);
        final InclusionProof proof = verification.inclusionProof(entries.get(2).id);

        final boolean valid = LedgerMerkleTree.verifyProof(proof, root);

        return new VerificationResult(root, proof, valid);
    }

    public record VerificationResult(String treeRoot, InclusionProof proof, boolean valid) {}
}
