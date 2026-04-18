package io.quarkiverse.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.model.LedgerMerkleFrontier;
import io.quarkiverse.ledger.runtime.service.LedgerMerkleTree;
import io.quarkiverse.ledger.runtime.service.model.InclusionProof;
import io.quarkiverse.ledger.service.supplement.TestEntry;

class LedgerMerkleTreeTest {

    private static final Instant FIXED_TIME = Instant.parse("2026-04-18T10:00:00Z");

    private TestEntry entry(UUID subjectId, int seq) {
        TestEntry e = new TestEntry();
        e.subjectId = subjectId;
        e.sequenceNumber = seq;
        e.entryType = LedgerEntryType.EVENT;
        e.actorId = "actor-" + seq;
        e.actorRole = "Tester";
        e.occurredAt = FIXED_TIME;
        return e;
    }

    private List<LedgerMerkleFrontier> buildFrontier(UUID subjectId, int n) {
        List<LedgerMerkleFrontier> frontier = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            String leaf = LedgerMerkleTree.leafHash(entry(subjectId, i));
            frontier = LedgerMerkleTree.append(leaf, frontier, subjectId);
        }
        return frontier;
    }

    // ── leafHash ─────────────────────────────────────────────────────────────

    @Test
    void leafHash_returns64CharHex() {
        assertThat(LedgerMerkleTree.leafHash(entry(UUID.randomUUID(), 1)))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void leafHash_isDeterministic() {
        UUID id = UUID.randomUUID();
        TestEntry e = entry(id, 1);
        assertThat(LedgerMerkleTree.leafHash(e)).isEqualTo(LedgerMerkleTree.leafHash(e));
    }

    @Test
    void leafHash_mutatingActorId_changesHash() {
        TestEntry e = entry(UUID.randomUUID(), 1);
        String before = LedgerMerkleTree.leafHash(e);
        e.actorId = "mutated";
        assertThat(LedgerMerkleTree.leafHash(e)).isNotEqualTo(before);
    }

    @Test
    void leafHash_andInternalHash_domainSeparated() {
        TestEntry e = entry(UUID.randomUUID(), 1);
        String leaf = LedgerMerkleTree.leafHash(e);
        String internal = LedgerMerkleTree.internalHash(leaf, leaf);
        assertThat(leaf).isNotEqualTo(internal);
    }

    // ── append — frontier size (Integer.bitCount(N)) ─────────────────────────

    @Test
    void append_n1_frontierHas1NodeAtLevel0() {
        UUID sub = UUID.randomUUID();
        List<LedgerMerkleFrontier> f = buildFrontier(sub, 1);
        assertThat(f).hasSize(1);
        assertThat(f.get(0).level).isEqualTo(0);
        assertThat(f.get(0).subjectId).isEqualTo(sub);
    }

    @Test
    void append_n2_frontierHas1NodeAtLevel1() {
        List<LedgerMerkleFrontier> f = buildFrontier(UUID.randomUUID(), 2);
        assertThat(f).hasSize(1);
        assertThat(f.get(0).level).isEqualTo(1);
    }

    @Test
    void append_n3_frontierHas2Nodes() {
        List<LedgerMerkleFrontier> f = buildFrontier(UUID.randomUUID(), 3);
        assertThat(f).hasSize(2);
        assertThat(f.stream().map(n -> n.level).toList()).containsExactlyInAnyOrder(0, 1);
    }

    @Test
    void append_n4_frontierHas1NodeAtLevel2() {
        List<LedgerMerkleFrontier> f = buildFrontier(UUID.randomUUID(), 4);
        assertThat(f).hasSize(1);
        assertThat(f.get(0).level).isEqualTo(2);
    }

    @Test
    void append_n5_frontierHas2Nodes() {
        List<LedgerMerkleFrontier> f = buildFrontier(UUID.randomUUID(), 5);
        assertThat(f).hasSize(2);
        assertThat(f.stream().map(n -> n.level).toList()).containsExactlyInAnyOrder(0, 2);
    }

    @Test
    void append_n7_frontierHas3Nodes() {
        List<LedgerMerkleFrontier> f = buildFrontier(UUID.randomUUID(), 7);
        assertThat(f).hasSize(3);
        assertThat(f.stream().map(n -> n.level).toList()).containsExactlyInAnyOrder(0, 1, 2);
    }

    @Test
    void append_n8_frontierHas1NodeAtLevel3() {
        List<LedgerMerkleFrontier> f = buildFrontier(UUID.randomUUID(), 8);
        assertThat(f).hasSize(1);
        assertThat(f.get(0).level).isEqualTo(3);
    }

    @Test
    void append_n2_level1NodeIsInternalHashOfBothLeaves() {
        UUID sub = UUID.randomUUID();
        String leaf1 = LedgerMerkleTree.leafHash(entry(sub, 1));
        String leaf2 = LedgerMerkleTree.leafHash(entry(sub, 2));
        List<LedgerMerkleFrontier> f1 = LedgerMerkleTree.append(leaf1, List.of(), sub);
        List<LedgerMerkleFrontier> f2 = LedgerMerkleTree.append(leaf2, f1, sub);
        assertThat(f2.get(0).hash).isEqualTo(LedgerMerkleTree.internalHash(leaf1, leaf2));
    }

    // ── treeRoot ─────────────────────────────────────────────────────────────

    @Test
    void treeRoot_singleNode_returnsThatHash() {
        UUID sub = UUID.randomUUID();
        List<LedgerMerkleFrontier> f = buildFrontier(sub, 1);
        String leaf = LedgerMerkleTree.leafHash(entry(sub, 1));
        assertThat(LedgerMerkleTree.treeRoot(f)).isEqualTo(leaf);
    }

    @Test
    void treeRoot_n2_equalsInternalHashOfLeaves() {
        UUID sub = UUID.randomUUID();
        String leaf1 = LedgerMerkleTree.leafHash(entry(sub, 1));
        String leaf2 = LedgerMerkleTree.leafHash(entry(sub, 2));
        List<LedgerMerkleFrontier> f = buildFrontier(sub, 2);
        assertThat(LedgerMerkleTree.treeRoot(f))
                .isEqualTo(LedgerMerkleTree.internalHash(leaf1, leaf2));
    }

    @Test
    void treeRoot_isDeterministic() {
        UUID sub = UUID.randomUUID();
        List<LedgerMerkleFrontier> f = buildFrontier(sub, 5);
        assertThat(LedgerMerkleTree.treeRoot(f)).isEqualTo(LedgerMerkleTree.treeRoot(f));
    }

    @Test
    void treeRoot_differentSubjects_differentRoots() {
        List<LedgerMerkleFrontier> f1 = buildFrontier(UUID.randomUUID(), 3);
        List<LedgerMerkleFrontier> f2 = buildFrontier(UUID.randomUUID(), 3);
        assertThat(LedgerMerkleTree.treeRoot(f1)).isNotEqualTo(LedgerMerkleTree.treeRoot(f2));
    }

    // ── inclusionProof + verifyProof ─────────────────────────────────────────

    private InclusionProof buildProofWithRoot(UUID sub, int n, int k) {
        List<String> leaves = new ArrayList<>();
        List<LedgerMerkleFrontier> frontier = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            String leaf = LedgerMerkleTree.leafHash(entry(sub, i));
            leaves.add(leaf);
            frontier = LedgerMerkleTree.append(leaf, frontier, sub);
        }
        String root = LedgerMerkleTree.treeRoot(frontier);
        InclusionProof proof = LedgerMerkleTree.inclusionProof(UUID.randomUUID(), k, n, leaves);
        return new InclusionProof(proof.entryId(), proof.entryIndex(), proof.treeSize(),
                proof.leafHash(), proof.siblings(), root);
    }

    @Test
    void inclusionProof_singleEntry_emptyProof_verifiesAsRoot() {
        UUID sub = UUID.randomUUID();
        InclusionProof proof = buildProofWithRoot(sub, 1, 0);
        assertThat(proof.siblings()).isEmpty();
        assertThat(LedgerMerkleTree.verifyProof(proof, proof.treeRoot())).isTrue();
    }

    @Test
    void inclusionProof_n2_entry0_verifies() {
        UUID sub = UUID.randomUUID();
        InclusionProof proof = buildProofWithRoot(sub, 2, 0);
        assertThat(LedgerMerkleTree.verifyProof(proof, proof.treeRoot())).isTrue();
    }

    @Test
    void inclusionProof_n2_entry1_verifies() {
        UUID sub = UUID.randomUUID();
        InclusionProof proof = buildProofWithRoot(sub, 2, 1);
        assertThat(LedgerMerkleTree.verifyProof(proof, proof.treeRoot())).isTrue();
    }

    @Test
    void inclusionProof_n4_allEntries_verify() {
        UUID sub = UUID.randomUUID();
        List<String> leaves = new ArrayList<>();
        List<LedgerMerkleFrontier> frontier = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            String leaf = LedgerMerkleTree.leafHash(entry(sub, i));
            leaves.add(leaf);
            frontier = LedgerMerkleTree.append(leaf, frontier, sub);
        }
        String root = LedgerMerkleTree.treeRoot(frontier);
        for (int k = 0; k < 4; k++) {
            InclusionProof proof = LedgerMerkleTree.inclusionProof(UUID.randomUUID(), k, 4, leaves);
            InclusionProof withRoot = new InclusionProof(proof.entryId(), k, 4,
                    proof.leafHash(), proof.siblings(), root);
            assertThat(LedgerMerkleTree.verifyProof(withRoot, root))
                    .as("entry %d of 4", k).isTrue();
        }
    }

    @Test
    void inclusionProof_n7_firstLastMiddle_verify() {
        UUID sub = UUID.randomUUID();
        List<String> leaves = new ArrayList<>();
        List<LedgerMerkleFrontier> frontier = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            String leaf = LedgerMerkleTree.leafHash(entry(sub, i));
            leaves.add(leaf);
            frontier = LedgerMerkleTree.append(leaf, frontier, sub);
        }
        String root = LedgerMerkleTree.treeRoot(frontier);
        for (int k : new int[] { 0, 3, 6 }) {
            InclusionProof proof = LedgerMerkleTree.inclusionProof(UUID.randomUUID(), k, 7, leaves);
            InclusionProof withRoot = new InclusionProof(proof.entryId(), k, 7,
                    proof.leafHash(), proof.siblings(), root);
            assertThat(LedgerMerkleTree.verifyProof(withRoot, root))
                    .as("entry %d of 7", k).isTrue();
        }
    }

    @Test
    void verifyProof_wrongRoot_returnsFalse() {
        UUID sub = UUID.randomUUID();
        InclusionProof proof = buildProofWithRoot(sub, 3, 1);
        assertThat(LedgerMerkleTree.verifyProof(proof,
                "0000000000000000000000000000000000000000000000000000000000000000")).isFalse();
    }
}
