package io.quarkiverse.ledger.runtime.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.ChronoUnit;
import java.util.List;

import io.quarkiverse.ledger.runtime.model.LedgerEntry;

/**
 * Static utility for computing and verifying the SHA-256 hash chain across ledger entries.
 *
 * <p>
 * Implements the Certificate Transparency pattern: each {@link LedgerEntry} carries a
 * {@code digest} field that is {@code SHA-256(previousDigest + "|" + canonicalContent)}.
 * The first entry for a subject uses the sentinel value {@code "GENESIS"} in place of a
 * previous digest. An auditor can recompute all digests from the raw data and detect any
 * tampering by comparing against stored values.
 *
 * <p>
 * Canonical content uses only base {@link LedgerEntry} fields — domain-specific subclass
 * fields are excluded to keep the chain domain-agnostic:
 * {@code subjectId|seqNum|entryType|actorId|actorRole|planRef|occurredAt}
 */
public final class LedgerHashChain {

    private static final String GENESIS = "GENESIS";

    private LedgerHashChain() {
    }

    /**
     * Compute the SHA-256 digest for the given entry chained from the previous hash.
     *
     * @param previousHash the {@code digest} of the previous entry for this subject,
     *        or {@code null} for the first entry (treated as {@code "GENESIS"})
     * @param entry the ledger entry whose canonical content is to be hashed
     * @return a 64-character lowercase hex SHA-256 digest
     * @throws IllegalStateException if SHA-256 is not available on this JVM
     */
    public static String compute(final String previousHash, final LedgerEntry entry) {
        final String canonical = String.join("|",
                entry.subjectId != null ? entry.subjectId.toString() : "",
                String.valueOf(entry.sequenceNumber),
                entry.entryType != null ? entry.entryType.name() : "",
                entry.actorId != null ? entry.actorId : "",
                entry.actorRole != null ? entry.actorRole : "",
                entry.planRef != null ? entry.planRef : "",
                // Truncate to milliseconds for consistent canonical form regardless of DB precision
                entry.occurredAt != null ? entry.occurredAt.truncatedTo(ChronoUnit.MILLIS).toString() : "");
        final String input = (previousHash != null ? previousHash : GENESIS) + "|" + canonical;
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder(64);
            for (final byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Return the sentinel string used as the {@code previousHash} for the first entry of a subject.
     *
     * @return {@code "GENESIS"}
     */
    public static String genesisHash() {
        return GENESIS;
    }

    /**
     * Verify the integrity of an ordered sequence of ledger entries by recomputing digests.
     *
     * <p>
     * Returns {@code false} on the first entry whose stored {@code digest} does not match
     * the computed value. Returns {@code true} only when all digests are consistent.
     *
     * @param entries ordered list of ledger entries for a single subject (ascending sequence)
     * @return {@code true} if the chain is intact; {@code false} if any entry has been tampered with
     */
    public static boolean verify(final List<LedgerEntry> entries) {
        String expectedPrevious = null;
        for (final LedgerEntry entry : entries) {
            final String computed = compute(expectedPrevious, entry);
            if (!computed.equals(entry.digest)) {
                return false;
            }
            expectedPrevious = entry.digest;
        }
        return true;
    }
}
