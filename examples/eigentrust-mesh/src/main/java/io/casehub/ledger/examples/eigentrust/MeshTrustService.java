package io.casehub.ledger.examples.eigentrust;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import io.casehub.ledger.runtime.model.ActorTrustScore;
import io.casehub.ledger.runtime.model.ActorType;
import io.casehub.ledger.runtime.model.AttestationVerdict;
import io.casehub.ledger.runtime.model.LedgerAttestation;
import io.casehub.ledger.runtime.model.LedgerEntryType;
import io.casehub.ledger.runtime.repository.ActorTrustScoreRepository;
import io.casehub.ledger.runtime.service.TrustScoreJob;

/**
 * Orchestrates the document classification mesh scenario.
 *
 * <p>
 * Three AI agents classify documents. After each classification, peer agents
 * attest to each other's work. The resulting attestation graph demonstrates
 * how EigenTrust propagates trust transitively.
 *
 * <p>
 * Agent roles:
 * <ul>
 *   <li>{@code classifier-a} — reliable agent; attested SOUND by b and c</li>
 *   <li>{@code classifier-b} — somewhat reliable; attested SOUND by c, CHALLENGED by a</li>
 *   <li>{@code classifier-c} — unreliable; attested FLAGGED by a and b</li>
 * </ul>
 */
@ApplicationScoped
public class MeshTrustService {

    static final String AGENT_A = "claude:classifier-a@v1";
    static final String AGENT_B = "claude:classifier-b@v1";
    static final String AGENT_C = "claude:classifier-c@v1";

    @Inject
    EntityManager em;

    @Inject
    TrustScoreJob trustScoreJob;

    @Inject
    ActorTrustScoreRepository trustRepo;

    /**
     * Seeds classification ledger entries and peer attestations for all three agents.
     * Idempotent for tests — uses unique document IDs each call.
     */
    @Transactional
    public void seedClassifications() {
        final Instant base = Instant.now().minus(1, ChronoUnit.DAYS);

        // Agent A classifies documents — reliable HIGH risk calls
        final DocumentClassificationLedgerEntry entryA1 = classify(AGENT_A, "HIGH", base);
        final DocumentClassificationLedgerEntry entryA2 = classify(AGENT_A, "HIGH", base.plusSeconds(60));

        // Agent B classifies documents — moderate, mostly correct
        final DocumentClassificationLedgerEntry entryB1 = classify(AGENT_B, "MEDIUM", base.plusSeconds(120));
        final DocumentClassificationLedgerEntry entryB2 = classify(AGENT_B, "MEDIUM", base.plusSeconds(180));

        // Agent C classifies documents — unreliable, gets risk levels wrong
        final DocumentClassificationLedgerEntry entryC1 = classify(AGENT_C, "LOW", base.plusSeconds(240));
        final DocumentClassificationLedgerEntry entryC2 = classify(AGENT_C, "LOW", base.plusSeconds(300));

        em.flush(); // ensure IDs are assigned before attestations reference them

        final Instant attBase = base.plusSeconds(600);

        // Peer reviews of Agent A's work — B and C both SOUND
        attest(AGENT_B, entryA1, AttestationVerdict.SOUND, 0.9, attBase);
        attest(AGENT_C, entryA1, AttestationVerdict.SOUND, 0.8, attBase.plusSeconds(10));
        attest(AGENT_B, entryA2, AttestationVerdict.SOUND, 0.9, attBase.plusSeconds(20));
        attest(AGENT_C, entryA2, AttestationVerdict.ENDORSED, 0.85, attBase.plusSeconds(30));

        // Peer reviews of Agent B's work — C gives SOUND, A gives CHALLENGED
        attest(AGENT_C, entryB1, AttestationVerdict.SOUND, 0.7, attBase.plusSeconds(40));
        attest(AGENT_A, entryB1, AttestationVerdict.CHALLENGED, 0.8, attBase.plusSeconds(50));
        attest(AGENT_C, entryB2, AttestationVerdict.SOUND, 0.7, attBase.plusSeconds(60));
        attest(AGENT_A, entryB2, AttestationVerdict.CHALLENGED, 0.8, attBase.plusSeconds(70));

        // Peer reviews of Agent C's work — A and B both FLAGGED
        attest(AGENT_A, entryC1, AttestationVerdict.FLAGGED, 0.95, attBase.plusSeconds(80));
        attest(AGENT_B, entryC1, AttestationVerdict.FLAGGED, 0.9, attBase.plusSeconds(90));
        attest(AGENT_A, entryC2, AttestationVerdict.FLAGGED, 0.95, attBase.plusSeconds(100));
        attest(AGENT_B, entryC2, AttestationVerdict.FLAGGED, 0.9, attBase.plusSeconds(110));
    }

    /**
     * Triggers trust score computation (both Bayesian Beta and EigenTrust).
     * Scheduler is disabled in tests — call this directly.
     */
    public void runTrustComputation() {
        trustScoreJob.runComputation();
    }

    /**
     * Returns all computed {@link ActorTrustScore} records.
     */
    public List<ActorTrustScore> getScores() {
        return trustRepo.findAll();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private DocumentClassificationLedgerEntry classify(
            final String agentId,
            final String riskLevel,
            final Instant occurredAt) {

        final DocumentClassificationLedgerEntry entry = new DocumentClassificationLedgerEntry();
        entry.subjectId = UUID.randomUUID();
        entry.sequenceNumber = 1;
        entry.entryType = LedgerEntryType.EVENT;
        entry.actorId = agentId;
        entry.actorType = ActorType.AGENT;
        entry.actorRole = "DocumentClassifier";
        entry.occurredAt = occurredAt;
        entry.documentId = UUID.randomUUID();
        entry.riskLevel = riskLevel;
        em.persist(entry);
        return entry;
    }

    private void attest(
            final String attestorId,
            final DocumentClassificationLedgerEntry entry,
            final AttestationVerdict verdict,
            final double confidence,
            final Instant occurredAt) {

        final LedgerAttestation att = new LedgerAttestation();
        att.ledgerEntryId = entry.id;
        att.subjectId = entry.subjectId;
        att.attestorId = attestorId;
        att.attestorType = ActorType.AGENT;
        att.attestorRole = "PeerReviewer";
        att.verdict = verdict;
        att.confidence = confidence;
        att.occurredAt = occurredAt;
        em.persist(att);
    }
}
