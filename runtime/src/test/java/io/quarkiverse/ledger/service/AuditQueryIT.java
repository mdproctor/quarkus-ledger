package io.quarkiverse.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.Test;

import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.repository.LedgerEntryRepository;
import io.quarkiverse.ledger.service.supplement.TestEntry;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for the three auditor-facing query methods on {@link LedgerEntryRepository}.
 */
@QuarkusTest
class AuditQueryIT {

    @Inject
    LedgerEntryRepository repo;

    private final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    private final Instant t1 = Instant.parse("2026-01-15T12:00:00Z");
    private final Instant t2 = Instant.parse("2026-01-31T23:59:59Z");
    private final Instant t3 = Instant.parse("2026-02-15T00:00:00Z");

    // ── findByActorId ─────────────────────────────────────────────────────────

    @Test
    @Transactional
    void findByActorId_returnsEntriesInRange() {
        final String actor = "audit-actor-" + UUID.randomUUID();
        seedEntry(actor, "Classifier", t1);
        seedEntry(actor, "Classifier", t2);
        seedEntry(actor, "Classifier", t3); // outside range

        final List<LedgerEntry> results = repo.findByActorId(actor, t0, t2);

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(e -> !e.occurredAt.isAfter(t2));
    }

    @Test
    @Transactional
    void findByActorId_boundaryInclusive() {
        final String actor = "audit-actor-" + UUID.randomUUID();
        seedEntry(actor, "Classifier", t1);
        seedEntry(actor, "Classifier", t2);

        final List<LedgerEntry> results = repo.findByActorId(actor, t1, t2);

        assertThat(results).hasSize(2);
    }

    @Test
    @Transactional
    void findByActorId_noEntries_returnsEmpty() {
        final List<LedgerEntry> results = repo.findByActorId("actor-that-does-not-exist-" + UUID.randomUUID(), t0, t3);

        assertThat(results).isEmpty();
    }

    @Test
    @Transactional
    void findByActorId_orderedByOccurredAtAsc() {
        final String actor = "audit-actor-" + UUID.randomUUID();
        seedEntry(actor, "Classifier", t2);
        seedEntry(actor, "Classifier", t1);

        final List<LedgerEntry> results = repo.findByActorId(actor, t0, t3);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).occurredAt).isEqualTo(t1);
        assertThat(results.get(1).occurredAt).isEqualTo(t2);
    }

    // ── findByActorRole ───────────────────────────────────────────────────────

    @Test
    @Transactional
    void findByActorRole_returnsEntriesInRange() {
        final String role = "Auditor-" + UUID.randomUUID();
        seedEntry("actor-a", role, t1);
        seedEntry("actor-b", role, t2);
        seedEntry("actor-c", role, t3); // outside range

        final List<LedgerEntry> results = repo.findByActorRole(role, t0, t2);

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(e -> e.actorRole.equals(role));
    }

    @Test
    @Transactional
    void findByActorRole_noEntries_returnsEmpty() {
        final List<LedgerEntry> results = repo.findByActorRole("NonExistentRole-" + UUID.randomUUID(), t0, t3);

        assertThat(results).isEmpty();
    }

    // ── findByTimeRange ───────────────────────────────────────────────────────

    @Test
    @Transactional
    void findByTimeRange_orderedByOccurredAtAsc() {
        final String marker = "timerange-order-" + UUID.randomUUID();
        seedEntry(marker, "Classifier", t2);
        seedEntry(marker, "Classifier", t1);

        final List<LedgerEntry> results = repo.findByTimeRange(t0, t3);
        final List<LedgerEntry> mine = results.stream()
                .filter(e -> marker.equals(e.actorId))
                .toList();

        assertThat(mine).hasSize(2);
        assertThat(mine.get(0).occurredAt).isEqualTo(t1);
        assertThat(mine.get(1).occurredAt).isEqualTo(t2);
    }

    // ── fixture ───────────────────────────────────────────────────────────────

    private void seedEntry(final String actorId, final String actorRole,
            final Instant occurredAt) {
        final TestEntry e = new TestEntry();
        e.subjectId = UUID.randomUUID();
        e.sequenceNumber = 1;
        e.entryType = LedgerEntryType.EVENT;
        e.actorId = actorId;
        e.actorType = ActorType.AGENT;
        e.actorRole = actorRole;
        e.occurredAt = occurredAt;
        e.persist();
    }
}
