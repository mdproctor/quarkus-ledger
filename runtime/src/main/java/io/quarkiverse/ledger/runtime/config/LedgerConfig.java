package io.quarkiverse.ledger.runtime.config;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
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
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
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

    /**
     * Retention enforcement — archives and removes ledger entries that have exceeded
     * their mandatory retention window, satisfying EU AI Act Article 12 record-keeping.
     *
     * @return retention sub-configuration
     */
    RetentionConfig retention();

    /**
     * Merkle tree settings — inclusion proofs and optional external publishing.
     *
     * @return the merkle sub-configuration
     */
    MerkleConfig merkle();

    /** Merkle Mountain Range and external publishing settings. */
    interface MerkleConfig {

        /**
         * External publishing settings. Publishing is inactive when {@code url} is absent.
         *
         * @return the publish sub-configuration
         */
        MerklePublishConfig publish();

        /** External checkpoint publishing settings. */
        interface MerklePublishConfig {

            /**
             * POST endpoint to receive signed tlog-checkpoint on each frontier update.
             * When absent, the publisher is inactive — zero overhead.
             *
             * @return the publish URL, if configured
             */
            java.util.Optional<String> url();

            /**
             * Path to an Ed25519 private key PEM file (PKCS#8 format).
             * Required when {@link #url()} is present.
             *
             * @return path to the private key file
             */
            java.util.Optional<String> privateKey();

            /**
             * Opaque identifier for the public key. Included in each checkpoint so
             * receivers can locate the corresponding public key for verification.
             *
             * @return the key identifier
             */
            @WithDefault("default")
            String keyId();
        }
    }

    /** Retention enforcement settings. */
    interface RetentionConfig {

        /**
         * When {@code true}, a nightly job archives and deletes entries older than
         * {@link #operationalDays()}. Off by default — zero behaviour change when disabled.
         *
         * @return {@code true} if retention enforcement is active; {@code false} by default
         */
        @WithDefault("false")
        boolean enabled();

        /**
         * Minimum retention window in days. Entries older than this are candidates for
         * archival. EU AI Act Article 12 requires at least 180 days (6 months).
         *
         * @return retention window in days (default 180)
         */
        @WithDefault("180")
        int operationalDays();

        /**
         * When {@code true}, each entry is written to {@code ledger_entry_archive}
         * before being deleted from {@code ledger_entry}. The archive record is
         * self-contained for reconstruction. Disabling skips the archive step and
         * deletes directly.
         *
         * @return {@code true} if archive-before-delete is enabled (default)
         */
        @WithDefault("true")
        boolean archiveBeforeDelete();
    }

    /** Hash chain tamper-evidence settings. */
    interface HashChainConfig {

        /**
         * When {@code true}, each {@link io.quarkiverse.ledger.runtime.model.LedgerEntry} carries
         * a Merkle leaf hash ({@code SHA-256(0x00 | canonical fields)}) and the per-subject
         * Merkle Mountain Range frontier is updated on every save. Setting this to {@code false}
         * skips leaf hash computation and frontier updates entirely — {@code LedgerEntry.digest}
         * will be {@code null} and inclusion proofs cannot be generated.
         *
         * @return {@code true} if Merkle leaf hash computation is enabled (default)
         */
        @WithDefault("true")
        boolean enabled();
    }

    /** Decision context snapshot settings. */
    interface DecisionContextConfig {

        /**
         * When {@code true}, a JSON snapshot of observable state is captured at each transition
         * and stored in {@code ComplianceSupplement.decisionContext}.
         * Addresses GDPR Article 22 and EU AI Act Article 12 explainability requirements.
         * Attach a {@link io.quarkiverse.ledger.runtime.model.supplement.ComplianceSupplement}
         * to the entry and populate its {@code decisionContext} field.
         *
         * @return {@code true} if decision context snapshots are enabled (default)
         */
        @WithDefault("true")
        boolean enabled();
    }

    /** Structured evidence capture settings. */
    interface EvidenceConfig {

        /**
         * When {@code true}, structured evidence is accepted and stored in
         * {@code ComplianceSupplement.evidence} on each ledger entry.
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

        /**
         * Forgiveness mechanism — modulates penalties for negative decisions based on
         * their age and the actor's overall negative decision frequency.
         *
         * <p>
         * When disabled (the default), {@link io.quarkiverse.ledger.runtime.service.TrustScoreComputer}
         * produces identical results to before this feature was introduced.
         *
         * @return forgiveness sub-configuration
         */
        ForgivenessConfig forgiveness();

        /** Forgiveness mechanism settings for trust score computation. */
        interface ForgivenessConfig {

            /**
             * When {@code true}, the forgiveness mechanism modulates the penalty of negative
             * decisions based on their age and the actor's negative decision frequency.
             * Off by default — enabling without {@code trust-score.enabled=true} has no effect.
             *
             * @return {@code true} if forgiveness is active; {@code false} by default
             */
            @WithDefault("false")
            boolean enabled();

            /**
             * Number of negative decisions at or below which the actor receives full
             * frequency leniency ({@code 1.0}). Above this threshold, leniency is halved
             * ({@code 0.5}), distinguishing one-off failures from repeat patterns.
             *
             * @return frequency threshold (default 3)
             */
            @WithDefault("3")
            int frequencyThreshold();

            /**
             * Half-life in days for the forgiveness recency decay. A failure this many days
             * in the past contributes 50% of its original penalty; at double the half-life,
             * 25%. Shorter values forgive faster. Independent of
             * {@link TrustScoreConfig#decayHalfLifeDays()}.
             *
             * @return forgiveness half-life in days (default 30)
             */
            @WithDefault("30")
            int halfLifeDays();
        }
    }
}
