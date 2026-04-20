package io.quarkiverse.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.Test;

import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.model.LedgerMerkleFrontier;
import io.quarkiverse.ledger.runtime.repository.LedgerEntryRepository;
import io.quarkiverse.ledger.runtime.service.LedgerMerkleTree;
import io.quarkiverse.ledger.runtime.service.LedgerVerificationService;
import io.quarkiverse.ledger.runtime.service.model.InclusionProof;
import io.quarkiverse.ledger.service.supplement.TestEntry;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class LedgerVerificationServiceIT {

    @Inject
    LedgerVerificationService verificationService;
    @Inject
    LedgerEntryRepository repo;
    @Inject
    EntityManager em;

    private TestEntry seedEntry(UUID subjectId, int seq, String actorId) {
        TestEntry e = new TestEntry();
        e.subjectId = subjectId;
        e.sequenceNumber = seq;
        e.entryType = LedgerEntryType.EVENT;
        e.actorId = actorId;
        e.actorType = ActorType.SYSTEM;
        e.actorRole = "Tester";
        return (TestEntry) repo.save(e);
    }

    @Test
    @Transactional
    void treeRoot_afterOneEntry_matchesFrontier() {
        UUID sub = UUID.randomUUID();
        seedEntry(sub, 1, "actor-a");

        String root = verificationService.treeRoot(sub);
        List<LedgerMerkleFrontier> frontier = em
                .createNamedQuery("LedgerMerkleFrontier.findBySubjectId", LedgerMerkleFrontier.class)
                .setParameter("subjectId", sub)
                .getResultList();

        assertThat(root).isNotNull().matches("[0-9a-f]{64}");
        assertThat(root).isEqualTo(LedgerMerkleTree.treeRoot(frontier));
    }

    @Test
    @Transactional
    void treeRoot_fiveEntries_frontierHasBitCount5Nodes() {
        UUID sub = UUID.randomUUID();
        for (int i = 1; i <= 5; i++)
            seedEntry(sub, i, "actor-" + i);

        verificationService.treeRoot(sub);

        List<LedgerMerkleFrontier> frontier = em
                .createNamedQuery("LedgerMerkleFrontier.findBySubjectId", LedgerMerkleFrontier.class)
                .setParameter("subjectId", sub)
                .getResultList();
        assertThat(frontier).hasSize(Integer.bitCount(5));
    }

    @Test
    @Transactional
    void inclusionProof_singleEntry_verifies() {
        UUID sub = UUID.randomUUID();
        TestEntry e = seedEntry(sub, 1, "actor-a");

        InclusionProof proof = verificationService.inclusionProof(e.id);

        assertThat(proof).isNotNull();
        assertThat(proof.entryId()).isEqualTo(e.id);
        assertThat(proof.entryIndex()).isEqualTo(0);
        assertThat(proof.treeSize()).isEqualTo(1);
        assertThat(proof.siblings()).isEmpty();
        assertThat(LedgerMerkleTree.verifyProof(proof, proof.treeRoot())).isTrue();
    }

    @Test
    @Transactional
    void inclusionProof_fourEntries_allVerify() {
        UUID sub = UUID.randomUUID();
        TestEntry[] entries = new TestEntry[4];
        for (int i = 0; i < 4; i++)
            entries[i] = seedEntry(sub, i + 1, "actor-" + i);

        String root = verificationService.treeRoot(sub);
        for (TestEntry e : entries) {
            InclusionProof proof = verificationService.inclusionProof(e.id);
            assertThat(LedgerMerkleTree.verifyProof(proof, root))
                    .as("entry %s should verify", e.id).isTrue();
        }
    }

    @Test
    @Transactional
    void inclusionProof_lastOfSevenEntries_verifies() {
        UUID sub = UUID.randomUUID();
        TestEntry last = null;
        for (int i = 1; i <= 7; i++)
            last = seedEntry(sub, i, "actor-" + i);

        String root = verificationService.treeRoot(sub);
        InclusionProof proof = verificationService.inclusionProof(last.id);
        assertThat(LedgerMerkleTree.verifyProof(proof, root)).isTrue();
    }

    @Test
    @Transactional
    void verify_untamperedChain_returnsTrue() {
        UUID sub = UUID.randomUUID();
        for (int i = 1; i <= 5; i++)
            seedEntry(sub, i, "actor-" + i);
        assertThat(verificationService.verify(sub)).isTrue();
    }

    @Test
    @Transactional
    void verify_afterDigestTampering_returnsFalse() {
        UUID sub = UUID.randomUUID();
        TestEntry e = seedEntry(sub, 1, "actor-a");

        LedgerEntry stored = repo.findEntryById(e.id).orElseThrow();
        stored.digest = "0000000000000000000000000000000000000000000000000000000000000000";
        repo.save(stored);

        assertThat(verificationService.verify(sub)).isFalse();
    }
}
