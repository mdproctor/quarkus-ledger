package io.casehub.ledger.runtime.service;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.arc.DefaultBean;

import io.casehub.ledger.runtime.model.LedgerAttestation;

/**
 * Default {@link GlobalScoreStrategy}: all attestations feed the global Beta model.
 *
 * <p>
 * Backed by Wang &amp; Vassileva (2003) — the global trust score is the root node of the
 * Bayesian trust network, computed from all interactions; capability-specific scores are
 * derived views on top of it.
 *
 * <p>
 * This is the {@code @DefaultBean} — activated automatically when no alternative
 * {@link GlobalScoreStrategy} is selected.
 */
@ApplicationScoped
@DefaultBean
public class AllAttestationsGlobalStrategy implements GlobalScoreStrategy {

    @Override
    public List<LedgerAttestation> selectAttestations(final List<LedgerAttestation> all) {
        return all;
    }
}
