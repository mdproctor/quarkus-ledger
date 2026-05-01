package io.casehub.ledger.api.model;

/**
 * Well-known capability tag sentinels for LedgerAttestation.
 */
public final class CapabilityTag {

    /** Attestation applies to all capabilities — the default. */
    public static final String GLOBAL = "*";

    private CapabilityTag() {
    }
}
