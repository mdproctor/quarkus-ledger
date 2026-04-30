package io.casehub.ledger.runtime.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.runtime.model.LedgerAttestation;

/**
 * Computes EigenTrust global trust scores via power iteration.
 *
 * <p>
 * Unlike the Bayesian Beta model in {@link TrustScoreComputer}, which scores actors from their
 * direct attestation history only, EigenTrust propagates trust transitively through the agent
 * mesh: if A trusts B and B trusts C, A has a derived signal about C.
 *
 * <p>
 * Algorithm: build a normalised trust matrix C where {@code C[i][j]} = the trust actor i places
 * in actor j, derived from positive/negative attestation counts (EigenTrust paper formulation).
 * Run power iteration with dampening: {@code t = (1-α) * Cᵀ * t + α * p}, where p is the
 * pre-trusted distribution. The resulting eigenvector t gives each actor's global trust share;
 * values sum to 1.0 across all actors.
 *
 * <p>
 * Actors with no positive attestation history use a uniform row (dangling-node treatment), which
 * keeps the matrix stochastic without concentrating trust on the pre-trusted set prematurely.
 *
 * <p>
 * Pure Java — no CDI, no database. Safe for unit tests without a Quarkus runtime.
 */
public final class EigenTrustComputer {

    static final int MAX_ITERATIONS = 100;
    static final double CONVERGENCE_THRESHOLD = 1e-6;

    private final double alpha;

    /**
     * @param alpha dampening constant in (0.0, 1.0); higher values anchor the result closer to
     *        the pre-trusted distribution and improve convergence in cyclic graphs. Values outside
     *        (0, 1) default to 0.15.
     */
    public EigenTrustComputer(final double alpha) {
        this.alpha = (alpha > 0.0 && alpha < 1.0) ? alpha : 0.15;
    }

    /**
     * Compute EigenTrust global trust scores for all actors found in the attestation data.
     *
     * @param allAttestations all ledger attestations to derive peer trust opinions from
     * @param entryActorIndex map from ledger entry id to the actor who made the decision
     * @param preTrustedActors actor IDs considered unconditionally trustworthy; if empty, a
     *        uniform distribution over all actors is used as the seed
     * @return map from actorId to global trust share in [0.0, 1.0]; values sum to ≤ 1.0 across
     *         all actors; empty if {@code allAttestations} is empty
     */
    public Map<String, Double> compute(
            final List<LedgerAttestation> allAttestations,
            final Map<UUID, String> entryActorIndex,
            final Set<String> preTrustedActors) {

        if (allAttestations.isEmpty()) {
            return Map.of();
        }

        // Collect all unique actors (attestors and decision-makers) in deterministic order
        final Set<String> actorSet = new LinkedHashSet<>();
        for (final LedgerAttestation att : allAttestations) {
            actorSet.add(att.attestorId);
            final String decisionActor = entryActorIndex.get(att.ledgerEntryId);
            if (decisionActor != null) {
                actorSet.add(decisionActor);
            }
        }

        final List<String> actors = new ArrayList<>(actorSet);
        final Map<String, Integer> actorIndex = new LinkedHashMap<>();
        for (int i = 0; i < actors.size(); i++) {
            actorIndex.put(actors.get(i), i);
        }
        final int n = actors.size();

        // s[i][j] = (positive attestations from i on j's decisions) - (negative)
        final double[][] s = new double[n][n];
        for (final LedgerAttestation att : allAttestations) {
            final String decisionActor = entryActorIndex.get(att.ledgerEntryId);
            if (decisionActor == null) {
                continue;
            }
            final Integer i = actorIndex.get(att.attestorId);
            final Integer j = actorIndex.get(decisionActor);
            if (i == null || j == null || i.equals(j)) {
                continue; // skip unknowns and self-attestation
            }
            if (att.verdict == AttestationVerdict.SOUND || att.verdict == AttestationVerdict.ENDORSED) {
                s[i][j] += 1.0;
            } else if (att.verdict == AttestationVerdict.FLAGGED || att.verdict == AttestationVerdict.CHALLENGED) {
                s[i][j] -= 1.0;
            }
        }

        // c[i][j] = max(s[i][j], 0) / sum_k max(s[i][k], 0)
        // Actors with no positive attestations get a uniform row (dangling-node fix)
        final double[][] c = new double[n][n];
        final double uniform = 1.0 / n;
        for (int i = 0; i < n; i++) {
            double rowSum = 0.0;
            for (int j = 0; j < n; j++) {
                rowSum += Math.max(s[i][j], 0.0);
            }
            if (rowSum > 0.0) {
                for (int j = 0; j < n; j++) {
                    c[i][j] = Math.max(s[i][j], 0.0) / rowSum;
                }
            } else {
                Arrays.fill(c[i], uniform);
            }
        }

        final double[] p = buildPreTrustedDistribution(actors, actorIndex, preTrustedActors, n);

        // Power iteration: t = (1-alpha) * Cᵀ * t + alpha * p
        double[] t = Arrays.copyOf(p, n);
        final double[] tNext = new double[n];
        final double oneMinusAlpha = 1.0 - alpha;

        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            Arrays.fill(tNext, 0.0);
            for (int i = 0; i < n; i++) {
                final double ti = t[i];
                if (ti == 0.0) {
                    continue;
                }
                for (int j = 0; j < n; j++) {
                    tNext[j] += c[i][j] * ti;
                }
            }

            double sum = 0.0;
            for (int j = 0; j < n; j++) {
                tNext[j] = oneMinusAlpha * tNext[j] + alpha * p[j];
                sum += tNext[j];
            }
            if (sum > 0.0) {
                for (int j = 0; j < n; j++) {
                    tNext[j] /= sum;
                }
            }

            double diff = 0.0;
            for (int j = 0; j < n; j++) {
                diff += Math.abs(tNext[j] - t[j]);
            }
            System.arraycopy(tNext, 0, t, 0, n);
            if (diff < CONVERGENCE_THRESHOLD) {
                break;
            }
        }

        final Map<String, Double> result = new LinkedHashMap<>();
        for (int j = 0; j < n; j++) {
            result.put(actors.get(j), Math.max(0.0, Math.min(1.0, t[j])));
        }
        return result;
    }

    private double[] buildPreTrustedDistribution(
            final List<String> actors,
            final Map<String, Integer> actorIndex,
            final Set<String> preTrustedActors,
            final int n) {

        final double[] p = new double[n];
        if (!preTrustedActors.isEmpty()) {
            int count = 0;
            for (final String pt : preTrustedActors) {
                final Integer idx = actorIndex.get(pt);
                if (idx != null) {
                    p[idx] = 1.0;
                    count++;
                }
            }
            if (count > 0) {
                for (int i = 0; i < n; i++) {
                    p[i] /= count;
                }
                return p;
            }
        }
        Arrays.fill(p, 1.0 / n);
        return p;
    }
}
