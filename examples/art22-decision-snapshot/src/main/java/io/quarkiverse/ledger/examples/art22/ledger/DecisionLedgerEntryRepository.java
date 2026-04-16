package io.quarkiverse.ledger.examples.art22.ledger;

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
 * Typed repository for {@link DecisionLedgerEntry}.
 *
 * <p>
 * Implements {@link LedgerEntryRepository} so it can be injected wherever the
 * base SPI is expected (e.g. {@link io.quarkiverse.ledger.runtime.service.TrustScoreJob}).
 *
 * <p>
 * {@code JpaLedgerEntryRepository} from quarkus-ledger is {@code @Alternative},
 * so this bean wins without any additional configuration.
 */
@ApplicationScoped
public class DecisionLedgerEntryRepository implements LedgerEntryRepository {

    // -------------------------------------------------------------------------
    // LedgerEntryRepository SPI
    // -------------------------------------------------------------------------

    @Override
    public LedgerEntry save(final LedgerEntry entry) {
        entry.persist();
        return entry;
    }

    @Override
    public List<LedgerEntry> findBySubjectId(final UUID subjectId) {
        return LedgerEntry.list(
                "subjectId = ?1 ORDER BY sequenceNumber ASC", subjectId);
    }

    @Override
    public Optional<LedgerEntry> findLatestBySubjectId(final UUID subjectId) {
        return LedgerEntry.find(
                "subjectId = ?1 ORDER BY sequenceNumber DESC", subjectId)
                .firstResultOptional();
    }

    @Override
    public Optional<LedgerEntry> findById(final UUID id) {
        return Optional.ofNullable(LedgerEntry.findById(id));
    }

    @Override
    public List<LedgerAttestation> findAttestationsByEntryId(final UUID entryId) {
        return LedgerAttestation.list(
                "ledgerEntryId = ?1 ORDER BY occurredAt ASC", entryId);
    }

    @Override
    public LedgerAttestation saveAttestation(final LedgerAttestation attestation) {
        attestation.persist();
        return attestation;
    }

    @Override
    public List<LedgerEntry> findAllEvents() {
        return LedgerEntry.find("entryType = ?1", LedgerEntryType.EVENT).list();
    }

    @Override
    public Map<UUID, List<LedgerAttestation>> findAttestationsForEntries(final Set<UUID> entryIds) {
        if (entryIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return LedgerAttestation.<LedgerAttestation> list("ledgerEntryId IN ?1", entryIds)
                .stream()
                .collect(Collectors.groupingBy(a -> a.ledgerEntryId));
    }
}
