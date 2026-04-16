package io.quarkiverse.ledger.examples.art22.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import io.quarkiverse.ledger.examples.art22.ledger.DecisionLedgerEntry;
import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.model.supplement.ComplianceSupplement;
import io.quarkiverse.ledger.runtime.service.LedgerHashChain;

/**
 * Simulates an AI decision service that records each decision with a full
 * GDPR Art.22 compliance supplement.
 */
@ApplicationScoped
public class DecisionService {

    @Transactional
    public DecisionLedgerEntry recordDecision(
            final String subjectId,
            final String category,
            final String outcome,
            final String algorithmRef,
            final double confidence,
            final String inputContext) {

        final UUID subjectUuid = UUID.fromString(subjectId);

        final List<DecisionLedgerEntry> existing = DecisionLedgerEntry
                .list("subjectId = ?1 order by sequenceNumber desc", subjectUuid);
        final int nextSeq = existing.isEmpty() ? 1 : existing.get(0).sequenceNumber + 1;
        final String previousHash = existing.isEmpty() ? null : existing.get(0).digest;

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

        entry.previousHash = previousHash;
        entry.digest = LedgerHashChain.compute(previousHash, entry);

        entry.persist();
        return entry;
    }

    public List<DecisionLedgerEntry> history(final String subjectId) {
        return DecisionLedgerEntry.list(
                "subjectId = ?1 order by sequenceNumber asc", UUID.fromString(subjectId));
    }
}
