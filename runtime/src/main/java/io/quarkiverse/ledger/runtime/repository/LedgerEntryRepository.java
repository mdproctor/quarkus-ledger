package io.quarkiverse.ledger.runtime.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.quarkiverse.ledger.runtime.model.LedgerAttestation;
import io.quarkiverse.ledger.runtime.model.LedgerEntry;

/**
 * SPI for persisting and querying {@link LedgerEntry} and {@link LedgerAttestation} records.
 *
 * <p>
 * Queries operate on the base {@link LedgerEntry} type — polymorphic results include all
 * registered subclasses. The default implementation uses Hibernate ORM with Panache.
 * Alternative implementations can be substituted via CDI.
 */
public interface LedgerEntryRepository {

    /**
     * Persist a new ledger entry and return the saved instance.
     *
     * @param entry the entry to persist; must not be {@code null}
     * @return the persisted entry (same instance, post-{@code @PrePersist})
     */
    LedgerEntry save(LedgerEntry entry);

    /**
     * Return all ledger entries for the given subject in sequence order (ascending).
     *
     * @param subjectId the aggregate identifier
     * @return ordered list of entries; empty if none exist
     */
    List<LedgerEntry> findBySubjectId(UUID subjectId);

    /**
     * Return the most recent ledger entry for the given subject, or empty if none.
     *
     * @param subjectId the aggregate identifier
     * @return the latest entry, or empty if no entries exist
     */
    Optional<LedgerEntry> findLatestBySubjectId(UUID subjectId);

    /**
     * Return a ledger entry by its primary key.
     *
     * @param id the ledger entry UUID primary key
     * @return the entry if found, or empty
     */
    Optional<LedgerEntry> findById(UUID id);

    /**
     * Return all attestations for the given ledger entry, ordered by occurrence time ascending.
     *
     * @param ledgerEntryId the ledger entry UUID
     * @return ordered list of attestations; empty if none exist
     */
    List<LedgerAttestation> findAttestationsByEntryId(UUID ledgerEntryId);

    /**
     * Persist a new attestation and return the saved instance.
     *
     * @param attestation the attestation to persist; must not be {@code null}
     * @return the persisted attestation
     */
    LedgerAttestation saveAttestation(LedgerAttestation attestation);

    /**
     * Return all EVENT-type ledger entries across all subjects (for trust score computation).
     *
     * @return list of all EVENT entries; empty if none exist
     */
    List<LedgerEntry> findAllEvents();

    /**
     * Return all attestations for the given set of ledger entry IDs, grouped by entry ID.
     *
     * @param entryIds the set of ledger entry UUIDs
     * @return map from entry ID to its attestations; empty map if {@code entryIds} is empty
     */
    Map<UUID, List<LedgerAttestation>> findAttestationsForEntries(Set<UUID> entryIds);

    /**
     * Return all ledger entries for the given actor whose {@code occurredAt} falls
     * within [{@code from}, {@code to}] inclusive, ordered by {@code occurredAt} ascending.
     *
     * <p>
     * Provides the auditor-facing reconstructability required by EU AI Act Art.12 and
     * GDPR Art.22: "show everything actor X did between dates Y and Z."
     *
     * <p>
     * Uses {@link Instant} rather than {@code LocalDateTime} — {@code occurredAt} is stored
     * as {@code Instant} throughout the codebase; {@code LocalDateTime} would require implicit
     * timezone conversion, creating a silent correctness hazard for distributed systems.
     *
     * @param actorId the actor identity to filter by
     * @param from start of the time range (inclusive)
     * @param to end of the time range (inclusive)
     * @return ordered list of entries; empty if none match
     */
    List<LedgerEntry> findByActorId(String actorId, Instant from, Instant to);

    /**
     * Return all ledger entries for the given actor role whose {@code occurredAt} falls
     * within [{@code from}, {@code to}] inclusive, ordered by {@code occurredAt} ascending.
     *
     * @param actorRole the functional role to filter by (e.g. {@code "Classifier"})
     * @param from start of the time range (inclusive)
     * @param to end of the time range (inclusive)
     * @return ordered list of entries; empty if none match
     */
    List<LedgerEntry> findByActorRole(String actorRole, Instant from, Instant to);

    /**
     * Return all ledger entries whose {@code occurredAt} falls within
     * [{@code from}, {@code to}] inclusive, ordered by {@code occurredAt} ascending.
     *
     * <p>
     * Use for bulk audit exports and retention window queries.
     *
     * @param from start of the time range (inclusive)
     * @param to end of the time range (inclusive)
     * @return ordered list of entries; empty if none match
     */
    List<LedgerEntry> findByTimeRange(Instant from, Instant to);
}
