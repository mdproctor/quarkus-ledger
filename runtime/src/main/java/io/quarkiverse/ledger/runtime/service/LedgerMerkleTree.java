package io.quarkiverse.ledger.runtime.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkiverse.ledger.runtime.model.LedgerMerkleFrontier;
import io.quarkiverse.ledger.runtime.service.model.InclusionProof;
import io.quarkiverse.ledger.runtime.service.model.ProofStep;
import io.quarkiverse.ledger.runtime.service.model.ProofStep.Side;

/**
 * Pure static utility implementing the Merkle Mountain Range (stored frontier) algorithm.
 *
 * <p>
 * Leaf hash: {@code SHA-256(0x00 | canonical_bytes)} — RFC 9162 domain separation.
 * Internal node hash: {@code SHA-256(0x01 | left_bytes | right_bytes)}.
 *
 * <p>
 * No CDI, no side effects. All state lives in the caller's frontier list.
 */
public final class LedgerMerkleTree {

    private LedgerMerkleTree() {
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Compute the RFC 9162 leaf hash for the given entry. */
    public static String leafHash(final LedgerEntry entry) {
        final byte[] canonical = canonicalBytes(entry);
        final byte[] input = new byte[1 + canonical.length];
        input[0] = 0x00;
        System.arraycopy(canonical, 0, input, 1, canonical.length);
        return sha256hex(input);
    }

    /** Compute an RFC 9162 internal node hash from two child hashes (hex strings). */
    public static String internalHash(final String leftHex, final String rightHex) {
        final byte[] left = hexToBytes(leftHex);
        final byte[] right = hexToBytes(rightHex);
        final byte[] input = new byte[1 + left.length + right.length];
        input[0] = 0x01;
        System.arraycopy(left, 0, input, 1, left.length);
        System.arraycopy(right, 0, input, 1 + left.length, right.length);
        return sha256hex(input);
    }

    /**
     * Append a new leaf to the frontier using binary-carry propagation.
     * Returns the complete new frontier (all surviving + new nodes).
     * Caller must delete DB nodes whose level is absent from the result.
     */
    public static List<LedgerMerkleFrontier> append(
            final String leafHash,
            final List<LedgerMerkleFrontier> frontier,
            final UUID subjectId) {

        final TreeMap<Integer, String> map = new TreeMap<>();
        for (final LedgerMerkleFrontier node : frontier) {
            map.put(node.level, node.hash);
        }

        String carry = leafHash;
        int level = 0;
        while (map.containsKey(level)) {
            carry = internalHash(map.get(level), carry);
            map.remove(level);
            level++;
        }
        map.put(level, carry);

        final List<LedgerMerkleFrontier> result = new ArrayList<>();
        for (final java.util.Map.Entry<Integer, String> e : map.entrySet()) {
            final LedgerMerkleFrontier node = new LedgerMerkleFrontier();
            node.subjectId = subjectId;
            node.level = e.getKey();
            node.hash = e.getValue();
            result.add(node);
        }
        return result;
    }

    /**
     * Compute the Merkle tree root from the frontier nodes.
     * Folds ASC by level: start with smallest-level, combine upward as
     * {@code internalHash(higher_level_node, current)}.
     */
    public static String treeRoot(final List<LedgerMerkleFrontier> frontier) {
        if (frontier.isEmpty()) {
            throw new IllegalArgumentException("frontier must not be empty");
        }
        final List<LedgerMerkleFrontier> sorted = frontier.stream()
                .sorted(Comparator.comparingInt(n -> n.level))
                .toList();
        String current = sorted.get(0).hash;
        for (int i = 1; i < sorted.size(); i++) {
            current = internalHash(sorted.get(i).hash, current);
        }
        return current;
    }

    /**
     * Generate an inclusion proof for the entry at 0-based index {@code k} in a tree of size {@code n}.
     * {@code leafHashes} must be the {@code digest} values of all entries in sequence order.
     */
    public static InclusionProof inclusionProof(
            final UUID entryId,
            final int k,
            final int n,
            final List<String> leafHashes) {

        final List<ProofStep> steps = new ArrayList<>();
        computeProof(k, n, leafHashes, steps);
        final String leafHash = leafHashes.get(k);
        // Compute root from all leaf hashes
        List<LedgerMerkleFrontier> frontier = new ArrayList<>();
        final UUID dummy = UUID.randomUUID();
        for (final String lh : leafHashes) {
            frontier = append(lh, frontier, dummy);
        }
        final String root = treeRoot(frontier);
        return new InclusionProof(entryId, k, n, leafHash, List.copyOf(steps), root);
    }

    /** Verify an inclusion proof against a known tree root. No DB access required. */
    public static boolean verifyProof(final InclusionProof proof, final String expectedRoot) {
        String current = proof.leafHash();
        for (final ProofStep step : proof.siblings()) {
            current = step.side() == Side.LEFT
                    ? internalHash(step.hash(), current)
                    : internalHash(current, step.hash());
        }
        return current.equals(expectedRoot);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static void computeProof(
            final int k, final int n,
            final List<String> leafHashes,
            final List<ProofStep> steps) {
        if (n == 1)
            return;
        final int split = Integer.highestOneBit(n - 1);
        if (k < split) {
            computeProof(k, split, leafHashes.subList(0, split), steps);
            steps.add(new ProofStep(subtreeRoot(leafHashes, split, n), Side.RIGHT));
        } else {
            computeProof(k - split, n - split, leafHashes.subList(split, n), steps);
            steps.add(new ProofStep(subtreeRoot(leafHashes, 0, split), Side.LEFT));
        }
    }

    private static String subtreeRoot(final List<String> leaves, final int from, final int to) {
        if (to - from == 1)
            return leaves.get(from);
        final int mid = from + Integer.highestOneBit(to - from - 1);
        return internalHash(subtreeRoot(leaves, from, mid), subtreeRoot(leaves, mid, to));
    }

    private static byte[] canonicalBytes(final LedgerEntry entry) {
        final String canonical = String.join("|",
                entry.subjectId != null ? entry.subjectId.toString() : "",
                String.valueOf(entry.sequenceNumber),
                entry.entryType != null ? entry.entryType.name() : "",
                entry.actorId != null ? entry.actorId : "",
                entry.actorRole != null ? entry.actorRole : "",
                entry.occurredAt != null
                        ? entry.occurredAt.truncatedTo(ChronoUnit.MILLIS).toString()
                        : "");
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] hexToBytes(final String hex) {
        final byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private static String sha256hex(final byte[] input) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] hash = md.digest(input);
            final StringBuilder sb = new StringBuilder(64);
            for (final byte b : hash)
                sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
