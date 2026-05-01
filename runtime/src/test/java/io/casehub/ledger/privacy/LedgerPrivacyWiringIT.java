package io.casehub.ledger.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.runtime.model.LedgerAttestation;
import io.casehub.ledger.runtime.model.LedgerEntry;
import io.casehub.ledger.runtime.model.supplement.ComplianceSupplement;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;
import io.casehub.ledger.service.supplement.TestEntry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Integration tests verifying write-path and query-path privacy wiring
 * in {@link io.casehub.ledger.runtime.repository.jpa.JpaLedgerEntryRepository}.
 */
@QuarkusTest
@TestProfile(InternalActorIdentityProviderIT.PseudonymisationProfile.class)
class LedgerPrivacyWiringIT {

    @Inject
    LedgerEntryRepository repo;

    @Inject
    EntityManager em;

    // ── Happy path: actorId stored as token, not raw ──────────────────────────

    @Test
    @Transactional
    void save_actorIdStoredAsToken_notRawIdentity() {
        final String rawActorId = "alice-" + UUID.randomUUID();
        final TestEntry entry = entry(rawActorId);
        repo.save(entry);

        final LedgerEntry stored = em.find(LedgerEntry.class, entry.id);
        assertThat(stored.actorId)
                .isNotNull()
                .isNotEqualTo(rawActorId);
    }

    // ── Happy path: findByActorId translates raw → token transparently ─────────

    @Test
    @Transactional
    void findByActorId_withRawActorId_returnsEntry() {
        final String rawActorId = "bob-" + UUID.randomUUID();
        final Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
        final Instant to = Instant.now().plus(1, ChronoUnit.HOURS);

        repo.save(entry(rawActorId));

        final List<LedgerEntry> results = repo.findByActorId(rawActorId, from, to);
        assertThat(results).hasSize(1);
    }

    // ── Correctness: attestorId tokenised on saveAttestation ─────────────────

    @Test
    @Transactional
    void saveAttestation_attestorIdStoredAsToken() {
        final String rawActorId = "carol-" + UUID.randomUUID();
        final String rawAttestorId = "attestor-" + UUID.randomUUID();
        final TestEntry entry = entry(rawActorId);
        repo.save(entry);

        final LedgerAttestation att = new LedgerAttestation();
        att.id = UUID.randomUUID();
        att.ledgerEntryId = entry.id;
        att.subjectId = entry.subjectId;
        att.attestorId = rawAttestorId;
        att.attestorType = ActorType.AGENT;
        att.verdict = AttestationVerdict.SOUND;
        att.confidence = 0.9;
        att.occurredAt = Instant.now();
        repo.saveAttestation(att);

        final LedgerAttestation stored = em.find(LedgerAttestation.class, att.id);
        assertThat(stored.attestorId)
                .isNotNull()
                .isNotEqualTo(rawAttestorId);
    }

    // ── Happy path: decisionContext passed through pass-through sanitiser ─────

    @Test
    @Transactional
    void save_decisionContext_storedUnchanged_withPassThroughSanitiser() {
        final String rawActorId = "dave-" + UUID.randomUUID();
        final TestEntry entry = entry(rawActorId);
        final ComplianceSupplement cs = new ComplianceSupplement();
        cs.decisionContext = "{\"riskScore\":42,\"region\":\"EU\"}";
        entry.attach(cs);

        repo.save(entry);

        final LedgerEntry stored = em.find(LedgerEntry.class, entry.id);
        stored.supplements.size(); // force lazy load
        assertThat(stored.compliance())
                .isPresent()
                .hasValueSatisfying(c -> assertThat(c.decisionContext).isEqualTo("{\"riskScore\":42,\"region\":\"EU\"}"));
    }

    // ── Correctness: same actorId on two entries → same token ─────────────────

    @Test
    @Transactional
    void save_sameActorId_twoEntries_sameTokenStored() {
        final String rawActorId = "eve-" + UUID.randomUUID();
        final TestEntry e1 = entry(rawActorId);
        final TestEntry e2 = entry(rawActorId);
        repo.save(e1);
        repo.save(e2);

        final String token1 = em.find(LedgerEntry.class, e1.id).actorId;
        final String token2 = em.find(LedgerEntry.class, e2.id).actorId;
        assertThat(token1).isEqualTo(token2);
    }

    // ── Correctness: findByActorId for unknown actorId returns empty ──────────

    @Test
    @Transactional
    void findByActorId_unknownActorId_returnsEmpty() {
        final Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
        final Instant to = Instant.now().plus(1, ChronoUnit.HOURS);

        assertThat(repo.findByActorId("never-saved-" + UUID.randomUUID(), from, to)).isEmpty();
    }

    // ── Happy path: findAttestationsByAttestorIdAndCapabilityTag uses tokenised attestorId ──

    @Test
    @Transactional
    void findByAttestorIdAndCapabilityTag_withPseudonymisation_findsTokenisedAttestation() {
        final String rawAttestorId = "attestor-" + UUID.randomUUID();
        final TestEntry entry = entry(rawAttestorId);
        repo.save(entry);

        final LedgerAttestation att = new LedgerAttestation();
        att.ledgerEntryId = entry.id;
        att.subjectId = entry.subjectId;
        att.attestorId = rawAttestorId;
        att.attestorType = ActorType.AGENT;
        att.verdict = AttestationVerdict.SOUND;
        att.confidence = 1.0;
        att.capabilityTag = "security-review";
        att.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        repo.saveAttestation(att);

        // Query by raw attestorId — tokeniseForQuery must translate to the stored token
        final var results = repo.findAttestationsByAttestorIdAndCapabilityTag(rawAttestorId, "security-review");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).capabilityTag).isEqualTo("security-review");
    }

    // ── Fixture ───────────────────────────────────────────────────────────────

    private TestEntry entry(final String actorId) {
        final TestEntry e = new TestEntry();
        e.subjectId = UUID.randomUUID();
        e.sequenceNumber = 1;
        e.entryType = LedgerEntryType.EVENT;
        e.actorId = actorId;
        e.actorType = ActorType.AGENT;
        e.actorRole = "Classifier";
        e.occurredAt = Instant.now();
        return e;
    }
}
