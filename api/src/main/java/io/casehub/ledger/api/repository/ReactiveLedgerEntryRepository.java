package io.casehub.ledger.api.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.casehub.ledger.api.model.LedgerAttestation;
import io.casehub.ledger.api.model.LedgerEntry;
import io.smallrye.mutiny.Uni;

/**
 * Reactive SPI for persisting and querying {@link LedgerEntry} records using Hibernate Reactive.
 *
 * <p>
 * Method signatures mirror {@link LedgerEntryRepository} with all return types wrapped in
 * {@link Uni}. Consumers building reactive Quarkus services should inject this interface
 * rather than {@link LedgerEntryRepository}.
 *
 * <p>
 * The default implementation {@code ReactiveJpaLedgerEntryRepository} is annotated
 * {@code @Alternative} — consumers activate it via {@code quarkus.arc.selected-alternatives}
 * or provide their own implementation.
 */
public interface ReactiveLedgerEntryRepository {

    Uni<LedgerEntry> save(LedgerEntry entry);

    Uni<List<LedgerEntry>> listAll();

    Uni<List<LedgerEntry>> findBySubjectId(UUID subjectId);

    Uni<Optional<LedgerEntry>> findLatestBySubjectId(UUID subjectId);

    Uni<Optional<LedgerEntry>> findEntryById(UUID id);

    Uni<List<LedgerEntry>> findAllEvents();

    Uni<List<LedgerEntry>> findByActorId(String actorId, Instant from, Instant to);

    Uni<List<LedgerEntry>> findByActorRole(String actorRole, Instant from, Instant to);

    Uni<List<LedgerEntry>> findByTimeRange(Instant from, Instant to);

    Uni<List<LedgerEntry>> findCausedBy(UUID entryId);

    Uni<LedgerAttestation> saveAttestation(LedgerAttestation attestation);

    Uni<List<LedgerAttestation>> findAttestationsByEntryId(UUID ledgerEntryId);

    Uni<Map<UUID, List<LedgerAttestation>>> findAttestationsForEntries(Set<UUID> entryIds);
}
