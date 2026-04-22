package io.quarkiverse.ledger.examples.art12.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.quarkiverse.ledger.examples.art12.ledger.DecisionEntry;
import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.model.supplement.ComplianceSupplement;
import io.quarkiverse.ledger.runtime.repository.LedgerEntryRepository;

/**
 * Application service for recording AI decisions and querying the audit log.
 *
 * <p>
 * Demonstrates EU AI Act Art.12 compliance by attaching a {@link ComplianceSupplement}
 * to every decision entry, capturing the algorithm reference, confidence score,
 * contestation URI, and human-override availability as required by Art.12(1)(g).
 */
@ApplicationScoped
public class AuditService {

    @Inject
    LedgerEntryRepository repo;

    @Transactional
    public DecisionEntry recordDecision(final String actorId, final String category,
            final String algorithmRef, final double confidence) {
        final UUID subjectId = UUID.randomUUID();
        final DecisionEntry e = new DecisionEntry();
        e.subjectId = subjectId;
        e.decisionCategory = category;
        e.sequenceNumber = 1;
        e.entryType = LedgerEntryType.EVENT;
        e.actorId = actorId;
        e.actorType = ActorType.AGENT;
        e.actorRole = "Classifier";
        e.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        final ComplianceSupplement cs = new ComplianceSupplement();
        cs.algorithmRef = algorithmRef;
        cs.confidenceScore = confidence;
        cs.contestationUri = "https://decisions.example.com/challenge/" + subjectId;
        cs.humanOverrideAvailable = true;
        e.attach(cs);

        repo.save(e);
        return e;
    }

    public List<LedgerEntry> auditByActor(final String actorId,
            final Instant from, final Instant to) {
        return repo.findByActorId(actorId, from, to);
    }

    public List<LedgerEntry> auditByTimeRange(final Instant from, final Instant to) {
        return repo.findByTimeRange(from, to);
    }
}
