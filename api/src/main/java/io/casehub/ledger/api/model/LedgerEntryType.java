package io.casehub.ledger.api.model;

/** Classifies a {@link LedgerEntry} as an intent, a fact, or a peer review record. */
public enum LedgerEntryType {
    /** The actor's declared intent before execution — e.g. "ApproveWorkItem". */
    COMMAND,
    /** The observable fact after execution — e.g. "WorkItemApproved". */
    EVENT,
    /** A peer attestation stamped onto an existing entry. */
    ATTESTATION
}
