package io.quarkiverse.ledger.runtime.repository.jpa;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.quarkiverse.ledger.runtime.model.LedgerAttestation;
import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.model.LedgerMerkleFrontier;
import io.quarkiverse.ledger.runtime.repository.LedgerEntryRepository;
import io.quarkiverse.ledger.runtime.service.LedgerMerklePublisher;
import io.quarkiverse.ledger.runtime.service.LedgerMerkleTree;

/**
 * Hibernate ORM / Panache implementation of {@link LedgerEntryRepository}.
 *
 * <p>
 * Queries on {@link LedgerEntry} are polymorphic — Hibernate joins to all registered
 * subclass tables and returns the correct concrete type for each row.
 *
 * <p>
 * Marked {@code @Alternative} so that domain-specific extensions (e.g. Tarkus's
 * {@code JpaWorkItemLedgerEntryRepository}) can provide a single, unambiguous
 * {@code LedgerEntryRepository} bean without CDI conflicts. When no domain-specific
 * implementation is present, this class must be activated via {@code beans.xml}.
 * In embedded deployments with a subclass-specific repository, no activation is needed
 * — the subclass repository handles all polymorphic {@code LedgerEntry} queries.
 */
@ApplicationScoped
@Alternative
public class JpaLedgerEntryRepository implements LedgerEntryRepository {

    @Inject
    LedgerMerklePublisher merklePublisher;

    /** {@inheritDoc} */
    @Override
    @Transactional
    public LedgerEntry save(final LedgerEntry entry) {
        // Ensure occurredAt is set before computing the digest — @PrePersist fires
        // during persist(), which is too late for leafHash() to see the correct value.
        if (entry.occurredAt == null) {
            entry.occurredAt = java.time.Instant.now();
        }
        entry.digest = LedgerMerkleTree.leafHash(entry);
        entry.persist();

        final List<LedgerMerkleFrontier> currentFrontier = LedgerMerkleFrontier.findBySubjectId(entry.subjectId);

        final List<LedgerMerkleFrontier> newFrontier = LedgerMerkleTree.append(entry.digest, currentFrontier, entry.subjectId);

        final Set<Integer> newLevels = newFrontier.stream()
                .map(n -> n.level)
                .collect(Collectors.toSet());
        for (final LedgerMerkleFrontier old : currentFrontier) {
            if (!newLevels.contains(old.level)) {
                LedgerMerkleFrontier.deleteBySubjectAndLevel(entry.subjectId, old.level);
            }
        }

        for (final LedgerMerkleFrontier node : newFrontier) {
            LedgerMerkleFrontier.deleteBySubjectAndLevel(entry.subjectId, node.level);
            node.persist();
        }

        final String newRoot = LedgerMerkleTree.treeRoot(newFrontier);
        merklePublisher.publish(entry.subjectId, entry.sequenceNumber, newRoot);

        return entry;
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> findBySubjectId(final UUID subjectId) {
        return LedgerEntry.list("subjectId = ?1 ORDER BY sequenceNumber ASC", subjectId);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<LedgerEntry> findLatestBySubjectId(final UUID subjectId) {
        return LedgerEntry.find("subjectId = ?1 ORDER BY sequenceNumber DESC", subjectId)
                .firstResultOptional();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<LedgerEntry> findById(final UUID id) {
        return Optional.ofNullable(LedgerEntry.findById(id));
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerAttestation> findAttestationsByEntryId(final UUID ledgerEntryId) {
        return LedgerAttestation.list("ledgerEntryId = ?1 ORDER BY occurredAt ASC", ledgerEntryId);
    }

    /** {@inheritDoc} */
    @Override
    public LedgerAttestation saveAttestation(final LedgerAttestation attestation) {
        attestation.persist();
        return attestation;
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> findAllEvents() {
        return LedgerEntry.find("entryType = ?1", LedgerEntryType.EVENT).list();
    }

    /** {@inheritDoc} */
    @Override
    public Map<UUID, List<LedgerAttestation>> findAttestationsForEntries(final Set<UUID> entryIds) {
        if (entryIds.isEmpty()) {
            return Collections.emptyMap();
        }
        final List<LedgerAttestation> all = LedgerAttestation.list("ledgerEntryId IN ?1", entryIds);
        return all.stream().collect(Collectors.groupingBy(a -> a.ledgerEntryId));
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> findByActorId(final String actorId,
            final Instant from, final Instant to) {
        return LedgerEntry.list(
                "actorId = ?1 AND occurredAt >= ?2 AND occurredAt <= ?3 ORDER BY occurredAt ASC",
                actorId, from, to);
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> findByActorRole(final String actorRole,
            final Instant from, final Instant to) {
        return LedgerEntry.list(
                "actorRole = ?1 AND occurredAt >= ?2 AND occurredAt <= ?3 ORDER BY occurredAt ASC",
                actorRole, from, to);
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> findByTimeRange(final Instant from, final Instant to) {
        return LedgerEntry.list(
                "occurredAt >= ?1 AND occurredAt <= ?2 ORDER BY occurredAt ASC",
                from, to);
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> findCausedBy(final UUID entryId) {
        return LedgerEntry.list(
                "causedByEntryId = ?1 ORDER BY occurredAt ASC", entryId);
    }
}
