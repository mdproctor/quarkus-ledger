package io.quarkiverse.ledger.examples.privacy.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.quarkiverse.ledger.examples.privacy.ledger.CreditApplicationLedgerEntry;
import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.model.supplement.ComplianceSupplement;
import io.quarkiverse.ledger.runtime.model.supplement.ProvenanceSupplement;
import io.quarkiverse.ledger.runtime.privacy.LedgerErasureService;
import io.quarkiverse.ledger.runtime.privacy.LedgerErasureService.ErasureResult;
import io.quarkiverse.ledger.runtime.repository.LedgerEntryRepository;

/**
 * Records credit application decisions with full privacy and supplement usage.
 *
 * <p>
 * Demonstrates:
 * <ul>
 * <li>Actor identity pseudonymisation — raw IDs (email addresses, personnel IDs) are replaced
 *     with UUID tokens by {@code JpaLedgerEntryRepository.save()} automatically when
 *     {@code quarkus.ledger.identity.tokenisation.enabled=true}.</li>
 * <li>{@link ComplianceSupplement#detail} — free-text explanation of the decision logic
 *     (the field most often missing from example code).</li>
 * <li>{@link ProvenanceSupplement#agentConfigHash} — SHA-256 of the LLM agent's configuration,
 *     enabling forensic detection of config drift within a persona version.</li>
 * <li>GDPR Art.17 erasure — severs the token→identity mapping; the audit record survives
 *     but the raw identity becomes permanently unresolvable.</li>
 * </ul>
 */
@ApplicationScoped
public class CreditApplicationService {

    @Inject
    LedgerEntryRepository repo;

    @Inject
    LedgerErasureService erasureService;

    /**
     * Records an AI risk agent's analysis of a credit application.
     *
     * <p>
     * The agent's persona name ({@code "claude:risk-agent@v1"}) is pseudonymised on save.
     * The {@link ComplianceSupplement} captures GDPR Art.22 fields including the {@code detail}
     * field that explains the scoring logic in plain language. The {@link ProvenanceSupplement}
     * binds this entry to the agent's configuration hash for forensic auditability.
     *
     * @param applicationId the credit application UUID (used as the ledger subject)
     * @param applicantId   raw applicant identity — included in decisionContext (will be sanitised
     *                      in production via a custom {@code DecisionContextSanitiser})
     * @param riskScore     model risk score in [0.0, 1.0]; above 0.7 triggers human review
     * @return the persisted ledger entry ID
     */
    @Transactional
    public UUID analyseApplication(final UUID applicationId, final String applicantId,
            final double riskScore) {
        final CreditApplicationLedgerEntry entry = new CreditApplicationLedgerEntry();
        entry.subjectId = applicationId;
        entry.applicationId = applicationId;
        entry.sequenceNumber = 1;
        entry.entryType = LedgerEntryType.EVENT;
        entry.actorId = "claude:risk-agent@v1";  // AI agent — versioned persona name
        entry.actorType = ActorType.AGENT;
        entry.actorRole = "RiskAnalyst";
        entry.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        entry.decisionType = "AnalyseApplication";
        entry.outcome = riskScore > 0.7 ? "REFERRED" : "APPROVED";

        // ComplianceSupplement — GDPR Art.22 fields including 'detail'
        final ComplianceSupplement cs = new ComplianceSupplement();
        cs.algorithmRef = "credit-risk-model-v3";
        cs.confidenceScore = riskScore;
        cs.humanOverrideAvailable = true;
        cs.contestationUri = "https://example.com/decisions/" + applicationId + "/challenge";
        cs.decisionContext = String.format(
                "{\"applicantId\":\"%s\",\"riskScore\":%.2f}", applicantId, riskScore);
        // detail: free-text explanation of the decision logic — the field most often missing
        cs.detail = "Risk score computed from income, credit history, and debt ratio. " +
                "Scores above 0.7 trigger mandatory human review.";
        entry.attach(cs);

        // ProvenanceSupplement — agentConfigHash binds this entry to the agent's config at session start
        final ProvenanceSupplement ps = new ProvenanceSupplement();
        ps.sourceEntitySystem = "credit-risk-platform";
        ps.sourceEntityType = "CreditApplication";
        ps.sourceEntityId = applicationId.toString();
        // In real use: SHA-256 hex digest of CLAUDE.md + system prompts, computed at session start.
        // Exactly 64 hex chars (256 bits). The "sha256:" label belongs in docs, not the column.
        ps.agentConfigHash = "a3f7c2e1b9d4ee82f3c1a7b56d9e2f04c8a1b3d7e9f2c5a8b1d4e7f0a3c6b9ef";
        entry.attach(ps);

        repo.save(entry);
        return entry.id;
    }

    /**
     * Records a human risk officer's review of a referred credit application.
     *
     * <p>
     * The officer's raw identity ({@code officerId}, typically an email address or HR ID)
     * is pseudonymised on save — the stored {@code actorId} will be a UUID token, not the
     * raw value. After GDPR Art.17 erasure, that token becomes permanently unresolvable.
     *
     * @param applicationId the credit application UUID
     * @param officerId     raw identity of the reviewing officer
     * @param approved      whether the officer approved or declined the application
     */
    @Transactional
    public void humanReview(final UUID applicationId, final String officerId,
            final boolean approved) {
        final CreditApplicationLedgerEntry entry = new CreditApplicationLedgerEntry();
        entry.subjectId = applicationId;
        entry.applicationId = applicationId;
        entry.sequenceNumber = 2;
        entry.entryType = LedgerEntryType.EVENT;
        entry.actorId = officerId;  // real human identity — pseudonymised automatically on save
        entry.actorType = ActorType.HUMAN;
        entry.actorRole = "RiskOfficer";
        entry.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        entry.decisionType = "ReviewDecision";
        entry.outcome = approved ? "APPROVED" : "DECLINED";

        final ComplianceSupplement cs = new ComplianceSupplement();
        cs.humanOverrideAvailable = false;  // this IS the human override
        cs.decisionContext = String.format(
                "{\"reviewerId\":\"%s\",\"decision\":\"%s\"}", officerId, entry.outcome);
        cs.rationale = approved
                ? "Risk assessment reviewed and accepted."
                : "Application declined after manual review.";
        entry.attach(cs);

        repo.save(entry);
    }

    /**
     * Process a GDPR Art.17 erasure request for the given actor identity.
     *
     * <p>
     * Severs the token→identity mapping in the {@code actor_identity} table.
     * Ledger entries that carried that actor's token are NOT deleted — they remain
     * as an intact, tamper-evident audit record. The personal data link is gone;
     * the token in the ledger becomes permanently unresolvable.
     *
     * @param rawActorId the real actor identity to erase (e.g. "alice@example.com")
     * @return erasure result with diagnostic information
     */
    public ErasureResult erase(final String rawActorId) {
        return erasureService.erase(rawActorId);
    }
}
