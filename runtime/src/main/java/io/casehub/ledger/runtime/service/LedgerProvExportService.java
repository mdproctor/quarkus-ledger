package io.casehub.ledger.runtime.service;

import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.casehub.ledger.runtime.model.LedgerEntry;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;

/**
 * CDI bean exporting a subject's audit history as W3C PROV-DM JSON-LD.
 *
 * <p>
 * Auto-activated — no consumer configuration required. Consumers may call
 * {@link #exportSubject(UUID)} directly or expose it via their own REST endpoint.
 *
 * <p>
 * See {@code docs/prov-dm-mapping.md} for the full field-by-field mapping.
 */
@ApplicationScoped
public class LedgerProvExportService {

    @Inject
    LedgerEntryRepository ledgerRepo;

    /**
     * Export the complete provenance graph for the given subject as PROV-JSON-LD.
     *
     * @param subjectId the aggregate identifier
     * @return pretty-printed PROV-JSON-LD string
     * @throws IllegalArgumentException if no entries exist for the subject
     */
    @Transactional
    public String exportSubject(final UUID subjectId) {
        final List<LedgerEntry> entries = ledgerRepo.findBySubjectId(subjectId);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("No entries found for subject: " + subjectId);
        }
        // Trigger lazy loading of supplements within the transaction boundary
        entries.forEach(e -> e.supplements.size());
        return LedgerProvSerializer.toProvJsonLd(subjectId, entries);
    }
}
