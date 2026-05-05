package io.casehub.ledger.service.intercept;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.runtime.model.LedgerEntry;
import io.casehub.ledger.runtime.model.supplement.ProvenanceSupplement;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;
import io.casehub.ledger.runtime.service.intercept.ProvenanceCapture;
import io.casehub.ledger.runtime.service.intercept.ProvenanceContext;
import io.casehub.ledger.runtime.service.intercept.SourceEntityId;
import io.casehub.ledger.service.supplement.TestEntry;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for the {@link ProvenanceCapture} CDI interceptor end-to-end.
 *
 * <p>Runs under the default test profile (standard H2, all features enabled).
 * Tests use CDI inner beans annotated with {@code @ProvenanceCapture} to trigger the interceptor.
 */
@QuarkusTest
class ProvenanceCaptureIT {

    /**
     * Test service with a {@code @ProvenanceCapture}-annotated method using
     * {@code @SourceEntityId} for explicit entity ID binding.
     */
    @ApplicationScoped
    static class WorkItemService {
        @Inject
        LedgerEntryRepository repo;

        @ProvenanceCapture(sourceEntityType = "WorkItem", sourceEntitySystem = "casehub-work")
        @Transactional
        public LedgerEntry complete(@SourceEntityId final UUID workItemId) {
            return repo.save(entry(workItemId));
        }

        @ProvenanceCapture(sourceEntityType = "Task")
        @Transactional
        public LedgerEntry completeTask(final UUID taskId) {
            // First UUID param — no @SourceEntityId, falls back to first UUID
            return repo.save(entry(taskId));
        }

        @Transactional
        public LedgerEntry uncaptured(final UUID someId) {
            return repo.save(entry(someId));
        }

        @ProvenanceCapture(sourceEntityType = "WorkItem")
        @Transactional
        public LedgerEntry throwingMethod(@SourceEntityId final UUID workItemId) {
            repo.save(entry(workItemId));
            throw new RuntimeException("simulated failure");
        }

        @ProvenanceCapture(sourceEntityType = "NoIdParam")
        @Transactional
        public LedgerEntry noUuidParam(final String stringParam) {
            // No UUID, no @SourceEntityId — sourceEntityId should be null
            final UUID subjectId = UUID.randomUUID();
            return repo.save(entry(subjectId));
        }

        @ProvenanceCapture(sourceEntityType = "WorkItem")
        @Transactional
        public LedgerEntry completeWithConfigHash(@SourceEntityId final UUID workItemId, final String configHash) {
            final TestEntry e = entry(workItemId);
            // Manually attach a supplement with agentConfigHash before repo.save().
            // The enricher must preserve this hash when it attaches the provenance context.
            final io.casehub.ledger.runtime.model.supplement.ProvenanceSupplement existing =
                    new io.casehub.ledger.runtime.model.supplement.ProvenanceSupplement();
            existing.agentConfigHash = configHash;
            e.attach(existing);
            return repo.save(e);
        }

        static TestEntry entry(final UUID subjectId) {
            final TestEntry e = new TestEntry();
            e.subjectId = subjectId;
            e.sequenceNumber = 1;
            e.entryType = LedgerEntryType.EVENT;
            e.actorId = "test-actor";
            e.actorType = ActorType.AGENT;
            e.actorRole = "Tester";
            e.occurredAt = Instant.now();
            return e;
        }
    }

    @Inject
    WorkItemService workItemService;

    @Inject
    LedgerEntryRepository repo;

    @Inject
    ProvenanceContext context;

    // ── Happy path: @SourceEntityId binding ───────────────────────────────────

    @Test
    void annotatedMethod_attachesProvenanceSupplement() {
        final UUID workItemId = UUID.randomUUID();
        final LedgerEntry saved = workItemService.complete(workItemId);

        final LedgerEntry found = repo.findEntryById(saved.id).orElseThrow();
        final ProvenanceSupplement ps = found.provenance().orElseThrow();

        assertThat(ps.sourceEntityType).isEqualTo("WorkItem");
        assertThat(ps.sourceEntitySystem).isEqualTo("casehub-work");
        assertThat(ps.sourceEntityId).isEqualTo(workItemId.toString());
    }

    // ── Happy path: first UUID fallback ───────────────────────────────────────

    @Test
    void firstUuidParam_usedAsEntityId_whenNoAnnotation() {
        final UUID taskId = UUID.randomUUID();
        final LedgerEntry saved = workItemService.completeTask(taskId);

        final ProvenanceSupplement ps = repo.findEntryById(saved.id).orElseThrow()
                .provenance().orElseThrow();

        assertThat(ps.sourceEntityType).isEqualTo("Task");
        assertThat(ps.sourceEntityId).isEqualTo(taskId.toString());
    }

    // ── Correctness: unannotated method → no supplement ───────────────────────

    @Test
    void unannotatedMethod_noSupplementAttached() {
        final LedgerEntry saved = workItemService.uncaptured(UUID.randomUUID());

        final LedgerEntry found = repo.findEntryById(saved.id).orElseThrow();
        assertThat(found.provenance()).isEmpty();
    }

    // ── Robustness: exception in method → context cleared afterward ───────────

    @Test
    void exceptionInMethod_contextClearedAfterward() {
        final UUID workItemId = UUID.randomUUID();

        assertThatThrownBy(() -> workItemService.throwingMethod(workItemId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated failure");

        // Context must be cleared — next call must not inherit the previous context
        assertThat(context.isActive()).isFalse();

        // Subsequent call works cleanly
        final LedgerEntry saved = workItemService.complete(UUID.randomUUID());
        assertThat(saved.provenance()).isPresent();
    }

    // ── Robustness: no UUID parameter → sourceEntityId null ───────────────────

    @Test
    void noUuidParam_sourceEntityIdIsNull() {
        final LedgerEntry saved = workItemService.noUuidParam("some-string");

        final ProvenanceSupplement ps = repo.findEntryById(saved.id).orElseThrow()
                .provenance().orElseThrow();

        assertThat(ps.sourceEntityType).isEqualTo("NoIdParam");
        assertThat(ps.sourceEntityId).isNull();
    }

    // ── Correctness: sequential calls don't leak context ─────────────────────

    @Test
    void sequentialCalls_contextDoesNotLeak() {
        final UUID workItemId = UUID.randomUUID();
        final UUID taskId = UUID.randomUUID();

        final LedgerEntry workItemEntry = workItemService.complete(workItemId);
        // Context must be clean between calls
        assertThat(context.isActive()).isFalse();
        final LedgerEntry taskEntry = workItemService.completeTask(taskId);

        final ProvenanceSupplement wps = repo.findEntryById(workItemEntry.id).orElseThrow()
                .provenance().orElseThrow();
        final ProvenanceSupplement tps = repo.findEntryById(taskEntry.id).orElseThrow()
                .provenance().orElseThrow();

        assertThat(wps.sourceEntityType).isEqualTo("WorkItem");
        assertThat(wps.sourceEntityId).isEqualTo(workItemId.toString());
        assertThat(tps.sourceEntityType).isEqualTo("Task");
        assertThat(tps.sourceEntityId).isEqualTo(taskId.toString());
    }

    // ── Correctness: agentConfigHash preserved when enricher replaces supplement ─

    @Test
    void agentConfigHash_preservedByEnricher() {
        final UUID workItemId = UUID.randomUUID();
        final String configHash = "a".repeat(64);

        final LedgerEntry saved = workItemService.completeWithConfigHash(workItemId, configHash);

        final ProvenanceSupplement ps = repo.findEntryById(saved.id).orElseThrow()
                .provenance().orElseThrow();

        // Enricher must preserve the caller-supplied agentConfigHash while also
        // writing the provenance context fields from @ProvenanceCapture.
        assertThat(ps.agentConfigHash).isEqualTo(configHash);
        assertThat(ps.sourceEntityType).isEqualTo("WorkItem");
        assertThat(ps.sourceEntityId).isEqualTo(workItemId.toString());
    }
}
