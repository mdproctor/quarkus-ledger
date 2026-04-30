package io.casehub.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.runtime.service.LedgerMerklePublisher;

class LedgerMerklePublisherTest {

    private static final String FAKE_ROOT = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";

    // ── Checkpoint format ─────────────────────────────────────────────────────

    @Test
    void buildCheckpoint_firstLineIsOrigin() {
        String cp = LedgerMerklePublisher.buildCheckpoint(UUID.randomUUID(), 5, FAKE_ROOT);
        assertThat(cp.split("\n")[0]).isEqualTo("io.casehub.ledger/v1");
    }

    @Test
    void buildCheckpoint_secondLineIsSubjectId() {
        UUID sub = UUID.randomUUID();
        String cp = LedgerMerklePublisher.buildCheckpoint(sub, 5, FAKE_ROOT);
        assertThat(cp.split("\n")[1]).isEqualTo(sub.toString());
    }

    @Test
    void buildCheckpoint_thirdLineIsTreeSize() {
        String cp = LedgerMerklePublisher.buildCheckpoint(UUID.randomUUID(), 42, FAKE_ROOT);
        assertThat(cp.split("\n")[2]).isEqualTo("42");
    }

    @Test
    void buildCheckpoint_fourthLineIsBase64Of32Bytes() {
        String cp = LedgerMerklePublisher.buildCheckpoint(UUID.randomUUID(), 1, FAKE_ROOT);
        String line4 = cp.split("\n")[3];
        byte[] decoded = Base64.getDecoder().decode(line4);
        assertThat(decoded).hasSize(32);
    }

    @Test
    void buildCheckpoint_fifthLineIsEmpty() {
        String cp = LedgerMerklePublisher.buildCheckpoint(UUID.randomUUID(), 1, FAKE_ROOT);
        String[] lines = cp.split("\n", -1);
        assertThat(lines).hasSizeGreaterThanOrEqualTo(5);
        assertThat(lines[4]).isEmpty();
    }

    // ── Ed25519 signing ───────────────────────────────────────────────────────

    @Test
    void signCheckpoint_producesVerifiableSignature() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair kp = kpg.generateKeyPair();

        String text = "io.casehub.ledger/v1\n" + UUID.randomUUID() + "\n5\n"
                + Base64.getEncoder().encodeToString(new byte[32]) + "\n";

        byte[] sig = LedgerMerklePublisher.signCheckpoint(text, kp.getPrivate());

        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(kp.getPublic());
        verifier.update(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(verifier.verify(sig)).isTrue();
    }

    @Test
    void signCheckpoint_differentInputs_differentSignatures() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair kp = kpg.generateKeyPair();
        byte[] sig1 = LedgerMerklePublisher.signCheckpoint("text-1", kp.getPrivate());
        byte[] sig2 = LedgerMerklePublisher.signCheckpoint("text-2", kp.getPrivate());
        assertThat(sig1).isNotEqualTo(sig2);
    }
}
