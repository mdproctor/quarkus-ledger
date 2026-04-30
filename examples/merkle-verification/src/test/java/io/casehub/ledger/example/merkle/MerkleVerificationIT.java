package io.casehub.ledger.example.merkle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.runtime.service.LedgerMerkleTree;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class MerkleVerificationIT {

    @Inject MerkleVerificationExample example;

    @Test
    void happyPath_fiveEntries_proofVerifiesWithoutDbAccess() {
        final MerkleVerificationExample.VerificationResult result =
                example.runHappyPath(UUID.randomUUID());

        assertThat(result.valid()).isTrue();
        assertThat(result.treeRoot()).matches("[0-9a-f]{64}");
        assertThat(result.proof().treeSize()).isEqualTo(5);
        assertThat(result.proof().entryIndex()).isEqualTo(2);
        assertThat(LedgerMerkleTree.verifyProof(result.proof(), result.treeRoot())).isTrue();
    }

    @Test
    void happyPath_wrongRoot_proofDoesNotVerify() {
        final MerkleVerificationExample.VerificationResult result =
                example.runHappyPath(UUID.randomUUID());
        final String wrongRoot =
                "0000000000000000000000000000000000000000000000000000000000000000";
        assertThat(LedgerMerkleTree.verifyProof(result.proof(), wrongRoot)).isFalse();
    }
}
