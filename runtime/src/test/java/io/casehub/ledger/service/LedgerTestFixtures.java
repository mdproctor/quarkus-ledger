package io.casehub.ledger.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.CapabilityTag;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.runtime.model.LedgerAttestation;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;
import io.casehub.ledger.service.supplement.TestEntry;

/**
 * Shared fixture helpers for integration tests that need ledger entries and attestations.
 * Use statically — callers inject their own repo and em.
 */
public final class LedgerTestFixtures {

    private LedgerTestFixtures() {
    }

    /**
     * Persist a {@link TestEntry} EVENT with an attestation 60 seconds after the decision.
     * Pass {@code null} for verdict to create an unattested entry.
     */
    public static TestEntry seedDecision(final String actorId, final Instant decisionTime,
            final AttestationVerdict verdictOrNull,
            final LedgerEntryRepository repo, final EntityManager em) {
        return seedDecision(actorId, decisionTime, verdictOrNull,
                verdictOrNull != null ? decisionTime.plusSeconds(60) : null,
                repo, em);
    }

    /**
     * Persist a {@link TestEntry} EVENT with an attestation at an explicit timestamp.
     * Pass {@code null} for verdict to create an unattested entry.
     */
    public static TestEntry seedDecision(final String actorId, final Instant decisionTime,
            final AttestationVerdict verdictOrNull, final Instant attestationTime,
            final LedgerEntryRepository repo, final EntityManager em) {

        final TestEntry entry = new TestEntry();
        entry.subjectId = UUID.randomUUID();
        entry.sequenceNumber = 1;
        entry.entryType = LedgerEntryType.EVENT;
        entry.actorId = actorId;
        entry.actorType = ActorType.AGENT;
        entry.actorRole = "Classifier";
        entry.occurredAt = decisionTime.truncatedTo(ChronoUnit.MILLIS);
        repo.save(entry);

        if (verdictOrNull != null) {
            final LedgerAttestation att = new LedgerAttestation();
            att.id = UUID.randomUUID();
            att.ledgerEntryId = entry.id;
            att.subjectId = entry.subjectId;
            att.attestorId = "compliance-bot";
            att.attestorType = ActorType.AGENT;
            att.verdict = verdictOrNull;
            att.confidence = 1.0;
            att.occurredAt = attestationTime.truncatedTo(ChronoUnit.MILLIS);
            em.persist(att);
        }

        return entry;
    }

    /**
     * Persist a {@link TestEntry} EVENT with an attestation at an explicit timestamp and
     * capability tag. Use {@link io.casehub.ledger.api.model.CapabilityTag#GLOBAL} for
     * cross-capability attestations.
     *
     * <p>Persists the attestation directly via {@link jakarta.persistence.EntityManager} —
     * bypasses {@link io.casehub.ledger.runtime.repository.LedgerEntryRepository#saveAttestation};
     * {@code attestorId} is stored without pseudonymisation.
     */
    public static TestEntry seedDecision(final String actorId, final Instant decisionTime,
            final AttestationVerdict verdictOrNull, final Instant attestationTime,
            final String capabilityTag,
            final LedgerEntryRepository repo, final EntityManager em) {

        final TestEntry entry = new TestEntry();
        entry.subjectId = UUID.randomUUID();
        entry.sequenceNumber = 1;
        entry.entryType = LedgerEntryType.EVENT;
        entry.actorId = actorId;
        entry.actorType = ActorType.AGENT;
        entry.actorRole = "Classifier";
        entry.occurredAt = decisionTime.truncatedTo(ChronoUnit.MILLIS);
        repo.save(entry);

        if (verdictOrNull != null) {
            final LedgerAttestation att = new LedgerAttestation();
            att.id = UUID.randomUUID();
            att.ledgerEntryId = entry.id;
            att.subjectId = entry.subjectId;
            att.attestorId = "compliance-bot";
            att.attestorType = ActorType.AGENT;
            att.verdict = verdictOrNull;
            att.confidence = 1.0;
            att.capabilityTag = capabilityTag;
            att.occurredAt = attestationTime.truncatedTo(ChronoUnit.MILLIS);
            em.persist(att);
        }

        return entry;
    }

    /**
     * Persist a {@link TestEntry} EVENT with a dimension-tagged attestation.
     *
     * <p>Persists the attestation directly via {@link jakarta.persistence.EntityManager}.
     * {@code verdict} is set to {@link io.casehub.ledger.api.model.AttestationVerdict#SOUND}
     * for all dimension attestations — the quality measurement is carried by
     * {@code dimensionScore}, not the verdict.
     *
     * @param actorId        the actor who made the decision
     * @param decisionTime   when the decision occurred
     * @param trustDimension the dimension label (e.g. "review-thoroughness")
     * @param dimensionScore continuous quality score in [0.0, 1.0]
     * @param capabilityTag  the capability tag (or {@link io.casehub.ledger.api.model.CapabilityTag#GLOBAL})
     * @param repo           entry repository for persisting the EVENT entry
     * @param em             entity manager for direct attestation persist
     */
    public static TestEntry seedDecisionWithDimension(final String actorId, final Instant decisionTime,
            final String trustDimension, final double dimensionScore,
            final String capabilityTag,
            final LedgerEntryRepository repo, final EntityManager em) {

        final TestEntry entry = new TestEntry();
        entry.subjectId = UUID.randomUUID();
        entry.sequenceNumber = 1;
        entry.entryType = LedgerEntryType.EVENT;
        entry.actorId = actorId;
        entry.actorType = ActorType.AGENT;
        entry.actorRole = "Reviewer";
        entry.occurredAt = decisionTime.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        repo.save(entry);

        final LedgerAttestation att = new LedgerAttestation();
        att.id = UUID.randomUUID();
        att.ledgerEntryId = entry.id;
        att.subjectId = entry.subjectId;
        att.attestorId = "dimension-assessor";
        att.attestorType = ActorType.AGENT;
        att.verdict = io.casehub.ledger.api.model.AttestationVerdict.SOUND;
        att.confidence = 1.0;
        att.capabilityTag = capabilityTag;
        att.trustDimension = trustDimension;
        att.dimensionScore = dimensionScore;
        att.occurredAt = decisionTime.plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        em.persist(att);

        return entry;
    }
}
