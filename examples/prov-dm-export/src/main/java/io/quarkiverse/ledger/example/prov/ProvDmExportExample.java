package io.quarkiverse.ledger.example.prov;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.model.supplement.ComplianceSupplement;
import io.quarkiverse.ledger.runtime.model.supplement.ProvenanceSupplement;
import io.quarkiverse.ledger.runtime.repository.LedgerEntryRepository;
import io.quarkiverse.ledger.runtime.service.LedgerProvExportService;

/**
 * Demonstrates PROV-DM JSON-LD export covering all supplement types.
 *
 * Happy path: 3 entries with ComplianceSupplement, ProvenanceSupplement,
 * and cross-subject causedByEntryId — export produces full PROV-JSON-LD.
 */
@ApplicationScoped
public class ProvDmExportExample {

    @Inject
    LedgerEntryRepository repo;
    @Inject
    LedgerProvExportService exportService;

    @Transactional
    public String runHappyPath(final UUID subjectId) {
        // Entry 1: AI decision with ComplianceSupplement
        ProvAuditEntry e1 = new ProvAuditEntry();
        e1.subjectId = subjectId;
        e1.sequenceNumber = 1;
        e1.entryType = LedgerEntryType.COMMAND;
        e1.actorId = "classifier-agent-v2";
        e1.actorType = ActorType.AGENT;
        e1.actorRole = "Classifier";
        ComplianceSupplement cs = new ComplianceSupplement();
        cs.algorithmRef = "gpt-4o";
        cs.confidenceScore = 0.94;
        cs.contestationUri = "https://example.com/challenge/" + subjectId;
        cs.humanOverrideAvailable = true;
        cs.planRef = "classification-policy-v3";
        cs.rationale = "Threshold exceeded; classified as high-priority";
        cs.decisionContext = "{\"inputScore\":0.94,\"threshold\":0.80}";
        e1.attach(cs);
        e1 = (ProvAuditEntry) repo.save(e1);

        // Entry 2: event from external WorkItem (ProvenanceSupplement)
        ProvAuditEntry e2 = new ProvAuditEntry();
        e2.subjectId = subjectId;
        e2.sequenceNumber = 2;
        e2.entryType = LedgerEntryType.EVENT;
        e2.actorId = "orchestrator-system";
        e2.actorType = ActorType.SYSTEM;
        e2.actorRole = "Orchestrator";
        ProvenanceSupplement ps = new ProvenanceSupplement();
        ps.sourceEntityId = "wi-" + UUID.randomUUID();
        ps.sourceEntityType = "WorkItem";
        ps.sourceEntitySystem = "tarkus";
        e2.attach(ps);
        e2 = (ProvAuditEntry) repo.save(e2);

        // Entry 3: caused by entry 1 (cross-subject causality)
        ProvAuditEntry e3 = new ProvAuditEntry();
        e3.subjectId = subjectId;
        e3.sequenceNumber = 3;
        e3.entryType = LedgerEntryType.EVENT;
        e3.actorId = "classifier-agent-v2";
        e3.actorType = ActorType.AGENT;
        e3.actorRole = "Classifier";
        e3.causedByEntryId = e1.id;
        repo.save(e3);

        return exportService.exportSubject(subjectId);
    }
}
