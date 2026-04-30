package io.casehub.ledger.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

/**
 * Quarkus build-time processor for the Ledger extension.
 *
 * <p>
 * Registers the "ledger" feature so it appears in the startup log:
 * {@code INFO features: [cdi, hibernate-orm, ledger, ...]}
 *
 * <p>
 * Additional {@code @BuildStep} methods will be added as the extension matures:
 * - Native image reflection configuration for {@code LedgerEntry} subclasses
 * - Validation that at least one subclass is registered
 * - Flyway migration resource registration for native builds
 */
class LedgerProcessor {

    private static final String FEATURE = "ledger";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }
}
