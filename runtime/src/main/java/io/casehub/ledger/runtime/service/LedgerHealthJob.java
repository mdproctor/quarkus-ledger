package io.casehub.ledger.runtime.service;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import io.casehub.ledger.runtime.config.LedgerConfig;
import io.casehub.ledger.runtime.persistence.LedgerPersistenceUnit;
import io.quarkus.scheduler.Scheduled;

/**
 * Scheduled health job that verifies audit completeness by:
 * <ol>
 * <li>Sequence gap detection — checks that per-subject sequence numbers are contiguous
 * (a gap indicates an entry was deleted after write).</li>
 * <li>Reconciliation — compares domain entity counts against ledger entry counts via
 * registered {@link LedgerReconciliationSource} SPI implementations.</li>
 * </ol>
 *
 * <p>
 * Gated by {@code casehub.ledger.health.enabled} (on by default). When disabled, the
 * scheduled trigger fires but immediately returns. The check interval is configurable
 * via {@code casehub.ledger.health.check-interval} (default {@code 1h}).
 *
 * <p>
 * Anomalies are signalled as {@link LedgerGapDetected} CDI events — consumers observe
 * them to log, alert, or trigger remediation. No data is modified.
 *
 * <p>
 * {@link #run()} is exposed with package-accessible visibility for direct invocation in
 * integration tests where the scheduler is disabled via the {@code health-test} profile.
 */
@ApplicationScoped
public class LedgerHealthJob {

    private static final Logger LOG = Logger.getLogger(LedgerHealthJob.class);

    @Inject
    @LedgerPersistenceUnit
    EntityManager em;

    @Inject
    LedgerConfig config;

    @Inject
    Event<LedgerGapDetected> gapEvent;

    @Inject
    @Any
    Instance<LedgerReconciliationSource> reconciliationSources;

    @Scheduled(every = "{casehub.ledger.health.check-interval:1h}", identity = "ledger-health-job")
    @Transactional
    public void runHealthChecks() {
        if (!config.health().enabled()) {
            return;
        }
        run();
    }

    /**
     * Execute all health checks. Exposed for direct invocation in integration tests.
     */
    @Transactional
    public void run() {
        checkSequenceGaps();
        checkReconciliation();
    }

    /**
     * For each subject, verify that sequence numbers are contiguous (no gaps).
     * Gap formula: a subject with entries spanning [min, max] should have exactly
     * {@code max - min + 1} entries. A lower actual count indicates deletion after write.
     */
    private void checkSequenceGaps() {
        @SuppressWarnings("unchecked")
        final List<Object[]> results = em.createQuery(
                "SELECT e.subjectId, COUNT(e), MIN(e.sequenceNumber), MAX(e.sequenceNumber) " +
                "FROM LedgerEntry e " +
                "GROUP BY e.subjectId " +
                "HAVING COUNT(e) != MAX(e.sequenceNumber) - MIN(e.sequenceNumber) + 1")
                .getResultList();

        for (final Object[] row : results) {
            final String subjectId = row[0].toString();
            final long actualCount = ((Number) row[1]).longValue();
            final long min = ((Number) row[2]).longValue();
            final long max = ((Number) row[3]).longValue();
            final long expectedCount = max - min + 1;

            LOG.warnf("Sequence gap detected for subject %s: expected %d entries (seq %d–%d), found %d",
                    subjectId, expectedCount, min, max, actualCount);
            gapEvent.fire(new LedgerGapDetected(subjectId, expectedCount, actualCount, GapType.SEQUENCE_GAP));
        }
    }

    private void checkReconciliation() {
        for (final LedgerReconciliationSource source : reconciliationSources) {
            if (!source.isActive()) {
                continue;
            }
            final long domainCount = source.countDomainEntities();
            final long ledgerCount = source.countLedgerEntries();
            if (domainCount != ledgerCount) {
                LOG.warnf("Reconciliation mismatch for %s: domain=%d, ledger=%d",
                        source.subjectType(), domainCount, ledgerCount);
                gapEvent.fire(new LedgerGapDetected(
                        source.subjectType(), domainCount, ledgerCount, GapType.RECONCILIATION_MISMATCH));
            }
        }
    }
}
