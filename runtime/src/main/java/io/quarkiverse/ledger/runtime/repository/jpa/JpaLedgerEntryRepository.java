package io.quarkiverse.ledger.runtime.repository.jpa;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.ledger.runtime.model.LedgerAttestation;
import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.repository.LedgerEntryRepository;

/**
 * Hibernate ORM / Panache implementation of {@link LedgerEntryRepository}.
 *
 * <p>
 * Queries on {@link LedgerEntry} are polymorphic — Hibernate joins to all registered
 * subclass tables and returns the correct concrete type for each row.
 */
@ApplicationScoped
public class JpaLedgerEntryRepository implements LedgerEntryRepository {

    /** {@inheritDoc} */
    @Override
    public LedgerEntry save(final LedgerEntry entry) {
        entry.persist();
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
}
