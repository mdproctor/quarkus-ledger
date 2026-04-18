package io.quarkiverse.ledger.runtime.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.quarkiverse.ledger.runtime.config.LedgerConfig;

/**
 * Publishes signed Merkle tree checkpoints to a configured external URL.
 * Inactive when {@code quarkus.ledger.merkle.publish.url} is absent.
 * Publishing is async and best-effort — failures are logged, not thrown.
 */
@ApplicationScoped
public class LedgerMerklePublisher {

    private static final Logger LOG = Logger.getLogger(LedgerMerklePublisher.class);

    @Inject
    LedgerConfig config;

    /**
     * Build an unsigned tlog-checkpoint (4 data lines + blank line = 5 lines total).
     * Format:
     *
     * <pre>
     * io.quarkiverse.ledger/v1
     * {subjectId}
     * {treeSize}
     * {base64(rootHashBytes)}
     *
     * </pre>
     */
    public static String buildCheckpoint(
            final UUID subjectId, final int treeSize,
            final String treeRoot) {

        final byte[] rootBytes = hexToBytes(treeRoot);
        return "io.quarkiverse.ledger/v1\n"
                + subjectId + "\n"
                + treeSize + "\n"
                + Base64.getEncoder().encodeToString(rootBytes) + "\n";
    }

    /** Sign the checkpoint text with an Ed25519 private key. Returns raw signature bytes. */
    public static byte[] signCheckpoint(final String checkpointText, final PrivateKey privateKey) {
        try {
            final Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(privateKey);
            sig.update(checkpointText.getBytes(StandardCharsets.UTF_8));
            return sig.sign();
        } catch (final Exception e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    /**
     * Build, sign, and POST the checkpoint for the given subject.
     * No-op when publish URL is not configured. Best-effort — logs failures.
     */
    public void publish(final UUID subjectId, final int treeSize, final String treeRoot) {
        final var publish = config.merkle().publish();
        if (publish.url().isEmpty())
            return;

        try {
            final String keyId = publish.keyId();
            final String checkpoint = buildCheckpoint(subjectId, treeSize, treeRoot);
            final PrivateKey privateKey = loadPrivateKey(publish.privateKey()
                    .orElseThrow(() -> new IllegalStateException(
                            "quarkus.ledger.merkle.publish.private-key required when url is set")));
            final byte[] signature = signCheckpoint(checkpoint, privateKey);
            final String signed = checkpoint
                    + "\n— " + keyId + " " + Base64.getEncoder().encodeToString(signature);

            final HttpClient client = HttpClient.newHttpClient();
            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(publish.url().get()))
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(signed))
                    .build();

            client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(ex -> {
                        LOG.warnf("Merkle checkpoint publish failed for subject %s: %s",
                                subjectId, ex.getMessage());
                        return null;
                    });
        } catch (final Exception e) {
            LOG.warnf("Merkle checkpoint publish error for subject %s: %s",
                    subjectId, e.getMessage());
        }
    }

    private static PrivateKey loadPrivateKey(final String pemPath) throws Exception {
        final String pem = Files.readString(Path.of(pemPath));
        final String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        final byte[] keyBytes = Base64.getDecoder().decode(base64);
        final KeyFactory kf = KeyFactory.getInstance("Ed25519");
        return kf.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
    }

    private static byte[] hexToBytes(final String hex) {
        final byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }
}
