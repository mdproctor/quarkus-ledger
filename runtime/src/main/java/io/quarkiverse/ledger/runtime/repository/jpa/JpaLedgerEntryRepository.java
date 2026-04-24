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
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import io.quarkiverse.ledger.runtime.config.LedgerConfig;
import io.quarkiverse.ledger.runtime.model.LedgerAttestation;
import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.model.LedgerMerkleFrontier;
import io.quarkiverse.ledger.runtime.persistence.LedgerPersistenceUnit;
import io.quarkiverse.ledger.runtime.privacy.ActorIdentityProvider;
import io.quarkiverse.ledger.runtime.privacy.DecisionContextSanitiser;
import io.quarkiverse.ledger.runtime.repository.LedgerEntryRepository;
import io.quarkiverse.ledger.runtime.service.LedgerMerklePublisher;
import io.quarkiverse.ledger.runtime.service.LedgerMerkleTree;

/**
 * Hibernate ORM implementation of {@link LedgerEntryRepository} using EntityManager directly.
 *
 * <p>
 * Queries on {@link LedgerEntry} are polymorphic — Hibernate joins to all registered
 * subclass tables and returns the correct concrete type for each row.
 *
 * <p>
 * {@link LedgerEntry} is a plain {@code @Entity} (not a PanacheEntityBase subclass), so
 * Panache repository bytecode enhancement cannot be used here. All queries go through
 * {@link EntityManager} directly.
 *
 * <p>
 * All queries use EntityManager and named queries directly.
 *
 * <p>
 * Marked {@code @Alternative} so that domain-specific extensions (e.g. Tarkus's
 * {@code JpaWorkItemLedgerEntryRepository}) can provide a single, unambiguous
 * {@code LedgerEntryRepository} bean without CDI conflicts.
 *
 * <p>
 * <b>Activation:</b> when no domain-specific repository is present (standalone deployments,
 * test modules, or extensions that use runtime services like {@code TrustScoreJob} without
 * a domain repo), activate this class via one of:
 * <ul>
 * <li>{@code quarkus.arc.selected-alternatives=io.quarkiverse.ledger.runtime.repository.jpa.JpaLedgerEntryRepository}
 * in {@code application.properties} (Quarkus-native, preferred)</li>
 * <li>{@code <alternatives>} in {@code META-INF/beans.xml} (standard CDI)</li>
 * <li>Subclass with {@code @ApplicationScoped} (inherits all polymorphic query logic)</li>
 * </ul>
 * When a domain-specific {@code LedgerEntryRepository} is present, no activation is needed —
 * this class stays dormant.
 */
@ApplicationScoped
@Alternative
public class JpaLedgerEntryRepository implements LedgerEntryRepository {

    @Inject
    @LedgerPersistenceUnit
    EntityManager em;

    @Inject
    LedgerConfig ledgerConfig;

    @Inject
    LedgerMerklePublisher merklePublisher;

    @Inject
    ActorIdentityProvider actorIdentityProvider;

    @Inject
    DecisionContextSanitiser decisionContextSanitiser;

    /** {@inheritDoc} */
    @Override
    @Transactional
    public LedgerEntry save(final LedgerEntry entry) {
        // Ensure occurredAt is set before computing the digest — @PrePersist fires
        // during persist(), which is too late for leafHash() to see the correct value.
        if (entry.occurredAt == null) {
            entry.occurredAt = Instant.now();
        }

        // Pseudonymise actor identity before computing the leaf hash.
        // The hash chain covers the token, not the raw identity.
        if (entry.actorId != null) {
            entry.actorId = actorIdentityProvider.tokenise(entry.actorId);
        }

        // Sanitise decisionContext in any attached ComplianceSupplement.
        entry.compliance().ifPresent(cs -> {
            if (cs.decisionContext != null) {
                cs.decisionContext = decisionContextSanitiser.sanitise(cs.decisionContext);
                entry.refreshSupplementJson();
            }
        });

        if (ledgerConfig.hashChain().enabled()) {
            entry.digest = LedgerMerkleTree.leafHash(entry);
        }
        em.persist(entry);

        if (ledgerConfig.hashChain().enabled()) {
            updateMerkleFrontier(entry);
        }

        return entry;
    }

    private void updateMerkleFrontier(final LedgerEntry entry) {
        final List<LedgerMerkleFrontier> currentFrontier = em
                .createNamedQuery("LedgerMerkleFrontier.findBySubjectId", LedgerMerkleFrontier.class)
                .setParameter("subjectId", entry.subjectId)
                .getResultList();

        final List<LedgerMerkleFrontier> newFrontier = LedgerMerkleTree.append(entry.digest, currentFrontier,
                entry.subjectId);

        final Set<Integer> newLevels = newFrontier.stream()
                .map(n -> n.level)
                .collect(Collectors.toSet());
        for (final LedgerMerkleFrontier old : currentFrontier) {
            if (!newLevels.contains(old.level)) {
                em.createNamedQuery("LedgerMerkleFrontier.deleteBySubjectAndLevel")
                        .setParameter("subjectId", entry.subjectId)
                        .setParameter("level", old.level)
                        .executeUpdate();
            }
        }

        for (final LedgerMerkleFrontier node : newFrontier) {
            em.createNamedQuery("LedgerMerkleFrontier.deleteBySubjectAndLevel")
                    .setParameter("subjectId", entry.subjectId)
                    .setParameter("level", node.level)
                    .executeUpdate();
            em.persist(node);
        }

        final String newRoot = LedgerMerkleTree.treeRoot(newFrontier);
        merklePublisher.publish(entry.subjectId, entry.sequenceNumber, newRoot);
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> findBySubjectId(final UUID subjectId) {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.subjectId = :subjectId ORDER BY e.sequenceNumber ASC",
                LedgerEntry.class)
                .setParameter("subjectId", subjectId)
                .getResultList();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<LedgerEntry> findLatestBySubjectId(final UUID subjectId) {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.subjectId = :subjectId ORDER BY e.sequenceNumber DESC",
                LedgerEntry.class)
                .setParameter("subjectId", subjectId)
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<LedgerEntry> findEntryById(final UUID id) {
        return Optional.ofNullable(em.find(LedgerEntry.class, id));
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerAttestation> findAttestationsByEntryId(final UUID ledgerEntryId) {
        return em.createNamedQuery("LedgerAttestation.findByEntryId", LedgerAttestation.class)
                .setParameter("entryId", ledgerEntryId)
                .getResultList();
    }

    /** {@inheritDoc} */
    @Override
    public LedgerAttestation saveAttestation(final LedgerAttestation attestation) {
        if (attestation.attestorId != null) {
            attestation.attestorId = actorIdentityProvider.tokenise(attestation.attestorId);
        }
        em.persist(attestation);
        return attestation;
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> listAll() {
        return em.createQuery("SELECT e FROM LedgerEntry e", LedgerEntry.class)
                .getResultList();
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> findAllEvents() {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.entryType = :type",
                LedgerEntry.class)
                .setParameter("type", LedgerEntryType.EVENT)
                .getResultList();
    }

    /** {@inheritDoc} */
    @Override
    public Map<UUID, List<LedgerAttestation>> findAttestationsForEntries(final Set<UUID> entryIds) {
        if (entryIds.isEmpty()) {
            return Collections.emptyMap();
        }
        final List<LedgerAttestation> all = em
                .createNamedQuery("LedgerAttestation.findByEntryIds", LedgerAttestation.class)
                .setParameter("entryIds", entryIds)
                .getResultList();
        return all.stream().collect(Collectors.groupingBy(a -> a.ledgerEntryId));
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> findByActorId(final String actorId,
            final Instant from, final Instant to) {
        final String token = actorIdentityProvider.tokeniseForQuery(actorId);
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.actorId = :actorId" +
                        " AND e.occurredAt >= :from AND e.occurredAt <= :to ORDER BY e.occurredAt ASC",
                LedgerEntry.class)
                .setParameter("actorId", token)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> findByActorRole(final String actorRole,
            final Instant from, final Instant to) {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.actorRole = :actorRole" +
                        " AND e.occurredAt >= :from AND e.occurredAt <= :to ORDER BY e.occurredAt ASC",
                LedgerEntry.class)
                .setParameter("actorRole", actorRole)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> findByTimeRange(final Instant from, final Instant to) {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.occurredAt >= :from AND e.occurredAt <= :to" +
                        " ORDER BY e.occurredAt ASC",
                LedgerEntry.class)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> findCausedBy(final UUID entryId) {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.causedByEntryId = :entryId ORDER BY e.occurredAt ASC",
                LedgerEntry.class)
                .setParameter("entryId", entryId)
                .getResultList();
    }
}
