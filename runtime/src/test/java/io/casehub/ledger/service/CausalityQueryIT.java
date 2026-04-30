package io.casehub.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.runtime.model.LedgerEntry;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;
import io.casehub.ledger.service.supplement.TestEntry;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for {@link LedgerEntryRepository#findCausedBy(UUID)}.
 *
 * <p>
 * Covers cross-system causal chain traversal: Claudony orchestrates Tarkus
 * which triggers Qhorus — each entry's {@code causedByEntryId} points upstream.
 */
@QuarkusTest
class CausalityQueryIT {

    @Inject
    LedgerEntryRepository repo;

    @Test
    @Transactional
    void findCausedBy_rootEntry_returnsDirectEffects() {
        final UUID rootId = UUID.randomUUID();
        final TestEntry ea = seedEntry("tarkus-worker", now().minus(2, ChronoUnit.MINUTES), rootId);
        final TestEntry eb = seedEntry("qhorus-agent", now().minus(1, ChronoUnit.MINUTES), rootId);
        seedEntry("unrelated", now(), null);

        final List<LedgerEntry> results = repo.findCausedBy(rootId);

        assertThat(results).hasSize(2);
        assertThat(results.stream().map(e -> e.id).toList())
                .containsExactlyInAnyOrder(ea.id, eb.id);
    }

    @Test
    @Transactional
    void findCausedBy_midChain_returnsOneHop() {
        final UUID rootId = UUID.randomUUID();
        final TestEntry tarkus = seedEntry("tarkus", now().minus(2, ChronoUnit.MINUTES), rootId);
        final TestEntry qhorus = seedEntry("qhorus", now().minus(1, ChronoUnit.MINUTES), tarkus.id);

        final List<LedgerEntry> results = repo.findCausedBy(tarkus.id);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id).isEqualTo(qhorus.id);
    }

    @Test
    @Transactional
    void findCausedBy_leaf_returnsEmpty() {
        final UUID rootId = UUID.randomUUID();
        final TestEntry leaf = seedEntry("leaf", now(), rootId);

        assertThat(repo.findCausedBy(leaf.id)).isEmpty();
    }

    @Test
    @Transactional
    void findCausedBy_unknownId_returnsEmpty() {
        assertThat(repo.findCausedBy(UUID.randomUUID())).isEmpty();
    }

    @Test
    @Transactional
    void findCausedBy_orderedByOccurredAtAsc() {
        final UUID rootId = UUID.randomUUID();
        final TestEntry late = seedEntry("agent-b", now().minus(1, ChronoUnit.MINUTES), rootId);
        final TestEntry early = seedEntry("agent-a", now().minus(5, ChronoUnit.MINUTES), rootId);

        final List<LedgerEntry> results = repo.findCausedBy(rootId);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).id).isEqualTo(early.id);
        assertThat(results.get(1).id).isEqualTo(late.id);
    }

    @Test
    @Transactional
    void fullOrchestrationChain_claudonyTarkusQhorus() {
        final UUID claudonyId = UUID.randomUUID();

        final TestEntry tarkus = seedEntry("tarkus-worker",
                now().minus(3, ChronoUnit.MINUTES), claudonyId);
        final TestEntry qhorus = seedEntry("qhorus-agent",
                now().minus(1, ChronoUnit.MINUTES), tarkus.id);

        // Hop 1: claudony → tarkus
        final List<LedgerEntry> hop1 = repo.findCausedBy(claudonyId);
        assertThat(hop1).hasSize(1);
        assertThat(hop1.get(0).id).isEqualTo(tarkus.id);
        assertThat(hop1.get(0).actorId).isEqualTo("tarkus-worker");

        // Hop 2: tarkus → qhorus
        final List<LedgerEntry> hop2 = repo.findCausedBy(tarkus.id);
        assertThat(hop2).hasSize(1);
        assertThat(hop2.get(0).id).isEqualTo(qhorus.id);
        assertThat(hop2.get(0).actorId).isEqualTo("qhorus-agent");

        // Leaf: qhorus causes nothing
        assertThat(repo.findCausedBy(qhorus.id)).isEmpty();
    }

    private TestEntry seedEntry(final String actorId, final Instant occurredAt,
            final UUID causedByEntryId) {
        final TestEntry e = new TestEntry();
        e.subjectId = UUID.randomUUID();
        e.sequenceNumber = 1;
        e.entryType = LedgerEntryType.EVENT;
        e.actorId = actorId;
        e.actorType = ActorType.AGENT;
        e.actorRole = "Processor";
        e.occurredAt = occurredAt;
        e.causedByEntryId = causedByEntryId;
        repo.save(e);
        return e;
    }

    private Instant now() {
        return Instant.now();
    }
}
