package io.casehub.ledger.examples.art22.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.casehub.ledger.examples.art22.ledger.DecisionLedgerEntry;
import io.casehub.ledger.examples.art22.ledger.DecisionLedgerEntryRepository;
import io.casehub.ledger.runtime.model.ActorType;
import io.casehub.ledger.runtime.model.LedgerEntryType;
import io.casehub.ledger.runtime.model.supplement.ComplianceSupplement;

/**
 * Simulates an AI decision service that records each decision with a full
 * GDPR Art.22 compliance supplement.
 */
@ApplicationScoped
public class DecisionService {

    @Inject
    DecisionLedgerEntryRepository repo;

    @Transactional
    public DecisionLedgerEntry recordDecision(
            final String subjectId,
            final String category,
            final String outcome,
            final String algorithmRef,
            final double confidence,
            final String inputContext) {

        final UUID subjectUuid = UUID.fromString(subjectId);

        final int nextSeq = repo.findLatestBySubjectId(subjectUuid)
                .map(e -> e.sequenceNumber + 1)
                .orElse(1);

        final DecisionLedgerEntry entry = new DecisionLedgerEntry();
        entry.subjectId = subjectUuid;
        entry.decisionId = UUID.randomUUID();
        entry.decisionCategory = category;
        entry.outcome = outcome;
        entry.sequenceNumber = nextSeq;
        entry.entryType = LedgerEntryType.EVENT;
        entry.actorId = algorithmRef;
        entry.actorType = ActorType.AGENT;
        entry.actorRole = "Classifier";
        entry.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        // Attach GDPR Art.22 compliance supplement — all four structured fields
        final ComplianceSupplement cs = new ComplianceSupplement();
        cs.algorithmRef = algorithmRef;
        cs.confidenceScore = confidence;
        cs.contestationUri = "https://decisions.example.com/challenge/" + entry.decisionId;
        cs.humanOverrideAvailable = true;
        cs.decisionContext = inputContext;
        entry.attach(cs);

        repo.save(entry);

        return entry;
    }

    public List<DecisionLedgerEntry> history(final String subjectId) {
        final UUID subjectUuid = UUID.fromString(subjectId);
        return repo.findBySubjectId(subjectUuid).stream()
                .filter(e -> e instanceof DecisionLedgerEntry)
                .map(e -> (DecisionLedgerEntry) e)
                .toList();
    }
}
