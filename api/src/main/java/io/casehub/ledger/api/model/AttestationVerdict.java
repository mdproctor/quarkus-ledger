package io.casehub.ledger.api.model;

/**
 * The formal verdict recorded in a {@link LedgerAttestation}.
 *
 * <p>
 * SOUND and ENDORSED are treated as positive signals in EigenTrust reputation computation.
 * FLAGGED and CHALLENGED are treated as negative signals.
 */
public enum AttestationVerdict {
    /** The attested entry is correct and complete. Positive signal. */
    SOUND,
    /** The attested entry needs review or contains concerns. Negative signal. */
    FLAGGED,
    /** The attested entry is explicitly endorsed by the attestor. Positive signal. */
    ENDORSED,
    /** The attested entry is formally disputed by the attestor. Negative signal. */
    CHALLENGED
}
