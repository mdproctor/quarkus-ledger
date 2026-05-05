package io.casehub.ledger.runtime.service;

/**
 * CDI event fired by {@link LedgerHealthJob} when a sequence gap or reconciliation
 * mismatch is detected.
 *
 * <p>
 * Consumers observe this event to log, alert, or trigger remediation:
 *
 * <pre>{@code
 * void onGap(@Observes LedgerGapDetected event) {
 *     if (event.type() == GapType.SEQUENCE_GAP) {
 *         log.errorf("Sequence gap for subject %s: expected %d, got %d",
 *             event.subjectId(), event.expectedCount(), event.actualCount());
 *     }
 * }
 * }</pre>
 *
 * @param subjectId     the subject identifier (UUID string) where the anomaly was detected;
 *                      for {@link GapType#RECONCILIATION_MISMATCH}, the subject type name from
 *                      {@link LedgerReconciliationSource#subjectType()}
 * @param expectedCount the count that should have been present (contiguous sequence length,
 *                      or domain entity count from the reconciliation source)
 * @param actualCount   the count actually found in the ledger
 * @param type          whether this is a sequence gap or a reconciliation mismatch
 */
public record LedgerGapDetected(
        String subjectId,
        long expectedCount,
        long actualCount,
        GapType type) {
}
