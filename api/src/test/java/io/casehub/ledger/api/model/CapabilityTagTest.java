package io.casehub.ledger.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CapabilityTag} constant and {@link LedgerAttestation#capabilityTag} default.
 * Pure JUnit 5 — no Quarkus runtime needed.
 */
class CapabilityTagTest {

    // ── Constant correctness ──────────────────────────────────────────────────

    @Test
    void global_constant_value_is_star() {
        assertThat(CapabilityTag.GLOBAL).isEqualTo("*");
    }

    // ── Field default ─────────────────────────────────────────────────────────

    @Test
    void ledgerAttestation_capabilityTag_defaultsToGlobal() {
        // LedgerAttestation is a @MappedSuperclass — instantiable as plain Java for field tests.
        final LedgerAttestation att = new LedgerAttestation();
        assertThat(att.capabilityTag)
                .as("capabilityTag must default to GLOBAL, never null")
                .isEqualTo(CapabilityTag.GLOBAL);
    }

    @Test
    void ledgerAttestation_capabilityTag_isNotNullByDefault() {
        assertThat(new LedgerAttestation().capabilityTag).isNotNull();
    }
}
