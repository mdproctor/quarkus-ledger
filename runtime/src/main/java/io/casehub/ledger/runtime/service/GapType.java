package io.casehub.ledger.runtime.service;

/** Classifies the anomaly detected by {@link LedgerHealthJob}. */
public enum GapType {

    /**
     * The sequence numbers for a subject are not contiguous — a ledger entry was
     * deleted after write (or sequence assignment skipped). Indicates tamper or bug.
     */
    SEQUENCE_GAP,

    /**
     * The count of domain entities reported by a {@link LedgerReconciliationSource}
     * does not match the count of ledger entries for that source. Indicates silently
     * dropped entries from the {@code REQUIRES_NEW} transaction pattern or missed writes.
     */
    RECONCILIATION_MISMATCH
}
