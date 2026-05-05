package io.casehub.ledger.runtime.service;

/**
 * SPI for domain-specific reconciliation checks in {@link LedgerHealthJob}.
 *
 * <p>
 * Consumers implement this interface to compare their domain entity count against the
 * corresponding ledger entry count. The health job fires a {@link LedgerGapDetected}
 * event when the counts differ, enabling operations to detect silently dropped entries
 * before filing regulatory reports.
 *
 * <p>
 * Only active sources (where {@link #isActive()} returns {@code true}) are evaluated.
 * This allows consumers to register a source that is conditionally active based on
 * configuration, avoiding false alerts in environments where the feature is not yet enabled.
 *
 * <p>
 * Example implementation:
 *
 * <pre>{@code
 * @ApplicationScoped
 * public class WorkItemReconciliationSource implements LedgerReconciliationSource {
 *
 *     @Inject WorkItemRepository workItemRepo;
 *     @Inject LedgerEntryRepository ledgerRepo;
 *     @Inject LedgerConfig ledgerConfig;
 *
 *     @Override public String subjectType()          { return "WorkItem"; }
 *     @Override public long countDomainEntities()    { return workItemRepo.count(); }
 *     @Override public long countLedgerEntries()     { return ledgerRepo.countByActorRole("WorkItemAgent"); }
 *     @Override public boolean isActive()            { return ledgerConfig.health().enabled(); }
 * }
 * }</pre>
 */
public interface LedgerReconciliationSource {

    /**
     * A human-readable name for the domain entity type being reconciled.
     * Used as the {@code subjectId} in the {@link LedgerGapDetected} event when a mismatch
     * is found. Example: {@code "WorkItem"}, {@code "Channel"}, {@code "CaseInstance"}.
     */
    String subjectType();

    /**
     * The total count of domain entities this source is responsible for.
     * Called only when {@link #isActive()} returns {@code true}.
     */
    long countDomainEntities();

    /**
     * The total count of corresponding ledger entries.
     * Called only when {@link #isActive()} returns {@code true}.
     */
    long countLedgerEntries();

    /**
     * Whether this source should participate in the current health check.
     * Return {@code false} to suppress reconciliation checks (e.g. when the feature
     * is disabled by configuration or the data is not yet populated).
     */
    boolean isActive();
}
