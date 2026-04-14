package io.quarkiverse.ledger.runtime.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration for the Quarkus Ledger extension.
 *
 * <p>
 * All keys are under the {@code quarkus.ledger} prefix. Every feature is independently
 * gated so consuming applications can enable only what they need.
 */
@ConfigMapping(prefix = "quarkus.ledger")
public interface LedgerConfig {

    /**
     * Master switch. When {@code false}, no ledger entries are written regardless of other settings.
     *
     * @return {@code true} if the ledger is enabled (default)
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Hash chain tamper-evidence settings.
     *
     * @return the hash chain sub-configuration
     */
    HashChainConfig hashChain();

    /**
     * Decision context snapshot settings.
     *
     * @return the decision context sub-configuration
     */
    DecisionContextConfig decisionContext();

    /**
     * Structured evidence capture settings.
     *
     * @return the evidence sub-configuration
     */
    EvidenceConfig evidence();

    /**
     * Peer attestation endpoint settings.
     *
     * @return the attestation sub-configuration
     */
    AttestationConfig attestations();

    /**
     * EigenTrust reputation computation settings.
     *
     * @return the trust score sub-configuration
     */
    TrustScoreConfig trustScore();

    /** Hash chain tamper-evidence settings. */
    interface HashChainConfig {

        /**
         * When {@code true}, each {@link io.quarkiverse.ledger.runtime.model.LedgerEntry} carries
         * a SHA-256 digest chained to the digest of the previous entry for the same subject.
         * Implements the Certificate Transparency pattern for offline tamper detection.
         *
         * @return {@code true} if hash chaining is enabled (default)
         */
        @WithDefault("true")
        boolean enabled();
    }

    /** Decision context snapshot settings. */
    interface DecisionContextConfig {

        /**
         * When {@code true}, a JSON snapshot of observable state is captured at each transition
         * and stored in {@code LedgerEntry.decisionContext}.
         * Addresses GDPR Article 22 and EU AI Act Article 12 explainability requirements.
         *
         * @return {@code true} if decision context snapshots are enabled (default)
         */
        @WithDefault("true")
        boolean enabled();
    }

    /** Structured evidence capture settings. */
    interface EvidenceConfig {

        /**
         * When {@code true}, structured evidence fields are accepted and stored per ledger entry.
         * Off by default — enabling without caller cooperation produces null evidence fields.
         *
         * @return {@code true} if evidence capture is enabled; {@code false} by default
         */
        @WithDefault("false")
        boolean enabled();
    }

    /** Peer attestation settings. */
    interface AttestationConfig {

        /**
         * When {@code true}, the attestation API is active and accepts peer attestations on entries.
         *
         * @return {@code true} if attestations are enabled (default)
         */
        @WithDefault("true")
        boolean enabled();
    }

    /** EigenTrust reputation computation settings. */
    interface TrustScoreConfig {

        /**
         * When {@code true}, a nightly scheduled job computes EigenTrust-inspired trust scores
         * from ledger history. Off by default — trust scores require accumulated history to be
         * meaningful; enabling on a new deployment produces unreliable early scores.
         *
         * @return {@code true} if trust score computation is enabled; {@code false} by default
         */
        @WithDefault("false")
        boolean enabled();

        /**
         * Exponential decay half-life in days for historical decision weighting.
         * Decisions older than this are down-weighted relative to recent ones.
         *
         * @return half-life in days (default 90)
         */
        @WithDefault("90")
        int decayHalfLifeDays();

        /**
         * When {@code true}, trust scores influence routing suggestions via CDI events.
         * Scores must be enabled and accumulated before enabling routing.
         *
         * @return {@code true} if trust-score-based routing is enabled; {@code false} by default
         */
        @WithDefault("false")
        boolean routingEnabled();
    }
}
