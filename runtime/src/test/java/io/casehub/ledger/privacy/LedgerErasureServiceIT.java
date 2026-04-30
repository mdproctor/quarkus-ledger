package io.casehub.ledger.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.runtime.privacy.LedgerErasureService;
import io.casehub.ledger.runtime.privacy.LedgerErasureService.ErasureResult;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;
import io.casehub.ledger.service.supplement.TestEntry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * End-to-end integration tests for {@link LedgerErasureService}.
 */
@QuarkusTest
@TestProfile(InternalActorIdentityProviderIT.PseudonymisationProfile.class)
class LedgerErasureServiceIT {

    @Inject
    LedgerErasureService erasureService;

    @Inject
    LedgerEntryRepository repo;

    // ── Happy path: erase known actor ─────────────────────────────────────────

    @Test
    @Transactional
    void erase_knownActor_mappingFound_correctCount() {
        final String actorId = "actor-" + UUID.randomUUID();

        saveEntry(actorId);
        saveEntry(actorId);
        saveEntry(actorId);

        final ErasureResult result = erasureService.erase(actorId);

        assertThat(result.rawActorId()).isEqualTo(actorId);
        assertThat(result.mappingFound()).isTrue();
        assertThat(result.affectedEntryCount()).isEqualTo(3L);
    }

    // ── Happy path: erase unknown actor ───────────────────────────────────────

    @Test
    @Transactional
    void erase_unknownActor_mappingNotFound_zeroCount() {
        final ErasureResult result = erasureService.erase("never-saved-" + UUID.randomUUID());

        assertThat(result.mappingFound()).isFalse();
        assertThat(result.affectedEntryCount()).isZero();
    }

    // ── End-to-end: findByActorId returns empty after erase ───────────────────

    @Test
    @Transactional
    void findByActorId_afterErase_returnsEmpty() {
        final String actorId = "erasable-" + UUID.randomUUID();
        final Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
        final Instant to = Instant.now().plus(1, ChronoUnit.HOURS);

        saveEntry(actorId);

        assertThat(repo.findByActorId(actorId, from, to)).hasSize(1);

        erasureService.erase(actorId);

        assertThat(repo.findByActorId(actorId, from, to)).isEmpty();
    }

    // ── Correctness: erase twice is idempotent ────────────────────────────────

    @Test
    @Transactional
    void erase_twice_secondReturnsNotFound() {
        final String actorId = "double-erase-" + UUID.randomUUID();
        saveEntry(actorId);

        erasureService.erase(actorId);
        final ErasureResult second = erasureService.erase(actorId);

        assertThat(second.mappingFound()).isFalse();
        assertThat(second.affectedEntryCount()).isZero();
    }

    // ── Fixture ───────────────────────────────────────────────────────────────

    private void saveEntry(final String actorId) {
        final TestEntry entry = new TestEntry();
        entry.subjectId = UUID.randomUUID();
        entry.sequenceNumber = 1;
        entry.entryType = LedgerEntryType.EVENT;
        entry.actorId = actorId;
        entry.actorType = ActorType.AGENT;
        entry.actorRole = "Classifier";
        entry.occurredAt = Instant.now();
        repo.save(entry);
    }
}
