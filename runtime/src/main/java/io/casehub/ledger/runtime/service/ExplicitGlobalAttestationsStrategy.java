package io.casehub.ledger.runtime.service;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.casehub.ledger.api.model.CapabilityTag;
import io.casehub.ledger.runtime.model.LedgerAttestation;

/**
 * Alternative {@link GlobalScoreStrategy}: only attestations explicitly tagged as global
 * ({@code capabilityTag = }{@link CapabilityTag#GLOBAL}) feed the global Beta model.
 *
 * <p>
 * Semantic: a global attestation is an explicit statement that the verdict applies across
 * all capabilities. Capability-specific attestations do not influence the global score.
 *
 * <p>
 * Activate via {@code quarkus.arc.selected-alternatives=
 * io.casehub.ledger.runtime.service.ExplicitGlobalAttestationsStrategy}.
 */
@ApplicationScoped
@Alternative
public class ExplicitGlobalAttestationsStrategy implements GlobalScoreStrategy {

    @Override
    public List<LedgerAttestation> selectAttestations(final List<LedgerAttestation> all) {
        return all.stream()
                .filter(a -> CapabilityTag.GLOBAL.equals(a.capabilityTag))
                .collect(Collectors.toList());
    }
}
