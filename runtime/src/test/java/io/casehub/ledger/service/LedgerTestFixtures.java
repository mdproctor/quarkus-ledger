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
}
