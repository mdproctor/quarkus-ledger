package io.quarkiverse.ledger.runtime.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import io.quarkiverse.ledger.runtime.config.LedgerConfig;
import io.quarkiverse.ledger.runtime.model.LedgerAttestation;
import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkiverse.ledger.runtime.model.LedgerEntryArchiveRecord;
import io.quarkiverse.ledger.runtime.persistence.LedgerPersistenceUnit;
import io.quarkiverse.ledger.runtime.repository.LedgerEntryRepository;
import io.quarkus.scheduler.Scheduled;

/**
 * Nightly scheduled job that enforces the configured retention window by archiving
 * and deleting ledger entries past their mandatory retention period.
 *
 * <p>
 * Gated by {@code quarkus.ledger.retention.enabled} (off by default). When disabled,
 * the scheduled trigger fires but immediately returns without touching any data.
 *
 * <p>
 * Each subject is processed in a single {@link Transactional} call — if archiving or
 * deletion fails for one subject, that transaction rolls back and the job continues to
 * the next subject. Retention runs are idempotent and safe to retry.
 *
 * <p>
 * Chain integrity is verified before any deletion. A subject with a broken hash chain
 * is skipped and a warning is logged.
 */
@ApplicationScoped
public class LedgerRetentionJob {

    private static final Logger LOG = Logger.getLogger(LedgerRetentionJob.class);

    @Inject
    LedgerEntryRepository ledgerRepo;

    @Inject
    @LedgerPersistenceUnit
    EntityManager entityManager;

    @Inject
    LedgerConfig config;

    @Inject
    LedgerVerificationService verificationService;

    /**
     * Scheduled entry point — runs every 24 hours.
     * Delegates to {@link #runRetention()} when retention is enabled.
     */
    @Scheduled(every = "24h", identity = "ledger-retention-job")
    @Transactional
    public void enforceRetention() {
        if (!config.retention().enabled()) {
            return;
        }
        runRetention();
    }

    /**
     * Perform the full retention run. Exposed for direct invocation in tests
     * (scheduler disabled in the retention-test profile).
     */
    @Transactional
    public void runRetention() {
        final Instant now = Instant.now();
        final int operationalDays = config.retention().operationalDays();
        final boolean archiveBeforeDelete = config.retention().archiveBeforeDelete();

        // Load ALL entries (not just EVENTs — retention covers all types)
        final List<LedgerEntry> all = ledgerRepo.listAll();
        final Map<UUID, List<LedgerEntry>> bySubject = all.stream()
                .filter(e -> e.subjectId != null)
                .collect(Collectors.groupingBy(e -> e.subjectId));

        final Map<UUID, List<LedgerEntry>> eligible = RetentionEligibilityChecker.eligibleSubjects(bySubject, now,
                operationalDays);

        int archived = 0;
        int skipped = 0;
        for (final Map.Entry<UUID, List<LedgerEntry>> entry : eligible.entrySet()) {
            try {
                archiveSubject(entry.getKey(), entry.getValue(), archiveBeforeDelete, now);
                archived++;
            } catch (final Exception e) {
                LOG.errorf(e, "Retention: skipping subject %s — %s",
                        entry.getKey(), e.getMessage());
                skipped++;
            }
        }
        LOG.infof("Retention run complete: %d subjects archived, %d skipped",
                archived, skipped);
    }

    private void archiveSubject(final UUID subjectId, final List<LedgerEntry> entries,
            final boolean archiveBeforeDelete, final Instant now) {

        // Sort by sequence number for deterministic chain verification
        final List<LedgerEntry> sorted = entries.stream()
                .sorted(Comparator.comparingInt(e -> e.sequenceNumber))
                .toList();

        // 1. Verify chain integrity — skip subject if broken
        if (!verificationService.verify(subjectId)) {
            throw new IllegalStateException(
                    "Hash chain integrity check failed for subject " + subjectId);
        }

        final Set<UUID> entryIds = sorted.stream()
                .map(e -> e.id)
                .collect(Collectors.toSet());

        // 2. Archive each entry (if configured)
        if (archiveBeforeDelete) {
            final Map<UUID, List<LedgerAttestation>> attestsByEntry = ledgerRepo.findAttestationsForEntries(entryIds);
            for (final LedgerEntry e : sorted) {
                final List<LedgerAttestation> attests = attestsByEntry.getOrDefault(e.id, List.of());
                final LedgerEntryArchiveRecord record = new LedgerEntryArchiveRecord();
                record.originalEntryId = e.id;
                record.subjectId = e.subjectId;
                record.sequenceNumber = e.sequenceNumber;
                record.entryJson = LedgerEntryArchiver.toJson(e, attests);
                record.entryOccurredAt = e.occurredAt;
                record.archivedAt = now;
                entityManager.persist(record);
            }
        }

        // 3. Delete attestations first — non-cascaded FK to ledger_entry
        entityManager.createNamedQuery("LedgerAttestation.findByEntryIds", LedgerAttestation.class)
                .setParameter("entryIds", entryIds)
                .getResultList()
                .forEach(entityManager::remove);

        // 4. Delete entries — JPA cascade handles: supplements → subclass rows → ledger_entry
        // Use em.find() rather than em.merge() — merge loses polymorphic subclass identity
        // for JOINED inheritance, causing OptimisticLockException (row count mismatch).
        // em.find() returns the correctly-typed managed entity in this EM's context.
        for (final LedgerEntry e : sorted) {
            final LedgerEntry managed = entityManager.find(LedgerEntry.class, e.id);
            if (managed != null) {
                entityManager.remove(managed);
            }
        }
    }
}
