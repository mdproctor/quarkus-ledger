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

import io.quarkiverse.ledger.runtime.model.LedgerAttestation;
import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.repository.ReactiveLedgerEntryRepository;
import io.quarkiverse.ledger.runtime.service.LedgerMerkleTree;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;

/**
 * Hibernate Reactive implementation of {@link ReactiveLedgerEntryRepository}.
 *
 * <p>
 * Annotated {@code @Alternative} — inactive by default. Reactive consumers activate
 * this bean (or provide their own implementation) via
 * {@code quarkus.arc.selected-alternatives} in {@code application.properties}.
 *
 * <p>
 * <strong>Note on Merkle frontier:</strong> {@link #save(LedgerEntry)} computes the
 * RFC 9162 leaf hash and sets {@code entry.digest}, but does NOT update the
 * {@code ledger_merkle_frontier} table. Frontier support in the reactive path requires
 * reactive entity operations on {@code LedgerMerkleFrontier} — planned for a future release.
 * {@code LedgerVerificationService.verify()} remains accurate for entries written through
 * the blocking path.
 */
@ApplicationScoped
@Alternative
public class ReactiveJpaLedgerEntryRepository
        implements ReactiveLedgerEntryRepository, PanacheRepositoryBase<LedgerEntry, UUID> {

    @Override
    public Uni<LedgerEntry> save(final LedgerEntry entry) {
        if (entry.occurredAt == null) {
            entry.occurredAt = Instant.now();
        }
        // Compute Merkle leaf hash — frontier update deferred to V2 (requires reactive frontier)
        entry.digest = LedgerMerkleTree.leafHash(entry);
        return persist(entry);
    }

    @Override
    public Uni<List<LedgerEntry>> findBySubjectId(final UUID subjectId) {
        return list("subjectId = ?1 ORDER BY sequenceNumber ASC", subjectId);
    }

    @Override
    public Uni<Optional<LedgerEntry>> findLatestBySubjectId(final UUID subjectId) {
        return find("subjectId = ?1 ORDER BY sequenceNumber DESC", subjectId)
                .firstResult()
                .map(Optional::ofNullable);
    }

    @Override
    public Uni<Optional<LedgerEntry>> findEntryById(final UUID id) {
        return find("id = ?1", id).firstResult().map(Optional::ofNullable);
    }

    @Override
    public Uni<List<LedgerEntry>> findAllEvents() {
        return list("entryType = ?1", LedgerEntryType.EVENT);
    }

    @Override
    public Uni<List<LedgerEntry>> findByActorId(final String actorId,
            final Instant from, final Instant to) {
        return list("actorId = ?1 AND occurredAt >= ?2 AND occurredAt <= ?3 ORDER BY occurredAt ASC",
                actorId, from, to);
    }

    @Override
    public Uni<List<LedgerEntry>> findByActorRole(final String actorRole,
            final Instant from, final Instant to) {
        return list("actorRole = ?1 AND occurredAt >= ?2 AND occurredAt <= ?3 ORDER BY occurredAt ASC",
                actorRole, from, to);
    }

    @Override
    public Uni<List<LedgerEntry>> findByTimeRange(final Instant from, final Instant to) {
        return list("occurredAt >= ?1 AND occurredAt <= ?2 ORDER BY occurredAt ASC", from, to);
    }

    @Override
    public Uni<List<LedgerEntry>> findCausedBy(final UUID entryId) {
        return list("causedByEntryId = ?1 ORDER BY occurredAt ASC", entryId);
    }

    @Override
    public Uni<LedgerAttestation> saveAttestation(final LedgerAttestation attestation) {
        return getSession()
                .flatMap(session -> session.persist(attestation).replaceWith(attestation));
    }

    @Override
    public Uni<List<LedgerAttestation>> findAttestationsByEntryId(final UUID ledgerEntryId) {
        return getSession()
                .flatMap(session -> session.createQuery(
                        "FROM LedgerAttestation WHERE ledgerEntryId = ?1 ORDER BY occurredAt ASC",
                        LedgerAttestation.class)
                        .setParameter(1, ledgerEntryId)
                        .getResultList());
    }

    @Override
    public Uni<Map<UUID, List<LedgerAttestation>>> findAttestationsForEntries(
            final Set<UUID> entryIds) {
        if (entryIds.isEmpty()) {
            return Uni.createFrom().item(Collections.emptyMap());
        }
        return getSession()
                .flatMap(session -> session.createQuery(
                        "FROM LedgerAttestation WHERE ledgerEntryId IN (?1)",
                        LedgerAttestation.class)
                        .setParameter(1, entryIds)
                        .getResultList())
                .map(list -> list.stream()
                        .collect(Collectors.groupingBy(a -> a.ledgerEntryId)));
    }
}
