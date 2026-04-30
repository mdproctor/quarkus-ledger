package io.casehub.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.runtime.model.LedgerEntry;
import io.casehub.ledger.runtime.service.RetentionEligibilityChecker;

/**
 * Unit tests for {@link RetentionEligibilityChecker} — no Quarkus runtime, no CDI.
 */
class RetentionEligibilityCheckerTest {

    private static class TestEntry extends LedgerEntry {
    }

    private final Instant now = Instant.now();

    private TestEntry entry(final UUID subjectId, final Instant occurredAt) {
        final TestEntry e = new TestEntry();
        e.id = UUID.randomUUID();
        e.subjectId = subjectId;
        e.sequenceNumber = 1;
        e.entryType = LedgerEntryType.EVENT;
        e.actorId = "actor";
        e.actorType = ActorType.AGENT;
        e.occurredAt = occurredAt;
        return e;
    }

    @Test
    void allEntriesOlderThanWindow_subjectIsEligible() {
        final UUID subject = UUID.randomUUID();
        final Map<UUID, List<LedgerEntry>> input = Map.of(subject, List.of(
                entry(subject, now.minus(40, ChronoUnit.DAYS)),
                entry(subject, now.minus(35, ChronoUnit.DAYS))));

        final Map<UUID, List<LedgerEntry>> result = RetentionEligibilityChecker.eligibleSubjects(input, now, 30);

        assertThat(result).containsKey(subject);
        assertThat(result.get(subject)).hasSize(2);
    }

    @Test
    void entryExactlyAtBoundary_isEligible() {
        // occurredAt == now - operationalDays → AT the cutoff → eligible
        final UUID subject = UUID.randomUUID();
        final Instant exactCutoff = now.minus(30, ChronoUnit.DAYS);
        final Map<UUID, List<LedgerEntry>> input = Map.of(subject, List.of(entry(subject, exactCutoff)));

        final Map<UUID, List<LedgerEntry>> result = RetentionEligibilityChecker.eligibleSubjects(input, now, 30);

        assertThat(result).containsKey(subject);
    }

    @Test
    void allEntriesNewerThanWindow_subjectNotEligible() {
        final UUID subject = UUID.randomUUID();
        final Map<UUID, List<LedgerEntry>> input = Map.of(subject, List.of(
                entry(subject, now.minus(10, ChronoUnit.DAYS))));

        final Map<UUID, List<LedgerEntry>> result = RetentionEligibilityChecker.eligibleSubjects(input, now, 30);

        assertThat(result).doesNotContainKey(subject);
    }

    @Test
    void entryOneDayBeforeBoundary_notEligible() {
        // occurredAt == now - (operationalDays - 1) → one day short → not eligible
        final UUID subject = UUID.randomUUID();
        final Instant oneDayShort = now.minus(29, ChronoUnit.DAYS);
        final Map<UUID, List<LedgerEntry>> input = Map.of(subject, List.of(entry(subject, oneDayShort)));

        final Map<UUID, List<LedgerEntry>> result = RetentionEligibilityChecker.eligibleSubjects(input, now, 30);

        assertThat(result).doesNotContainKey(subject);
    }

    @Test
    void mixedAges_oneNewEntry_subjectNotEligible() {
        // All-or-nothing: one new entry means the whole subject is excluded
        final UUID subject = UUID.randomUUID();
        final Map<UUID, List<LedgerEntry>> input = Map.of(subject, List.of(
                entry(subject, now.minus(60, ChronoUnit.DAYS)),
                entry(subject, now.minus(45, ChronoUnit.DAYS)),
                entry(subject, now.minus(5, ChronoUnit.DAYS)) // too new
        ));

        final Map<UUID, List<LedgerEntry>> result = RetentionEligibilityChecker.eligibleSubjects(input, now, 30);

        assertThat(result).doesNotContainKey(subject);
    }

    @Test
    void emptyInput_returnsEmptyMap() {
        final Map<UUID, List<LedgerEntry>> result = RetentionEligibilityChecker.eligibleSubjects(Map.of(), now, 30);

        assertThat(result).isEmpty();
    }

    @Test
    void emptySubjectList_subjectExcluded() {
        final UUID subject = UUID.randomUUID();
        final Map<UUID, List<LedgerEntry>> input = Map.of(subject, List.of());

        final Map<UUID, List<LedgerEntry>> result = RetentionEligibilityChecker.eligibleSubjects(input, now, 30);

        assertThat(result).doesNotContainKey(subject);
    }

    @Test
    void multipleSubjects_onlyOldOnesReturned() {
        final UUID oldSubject = UUID.randomUUID();
        final UUID newSubject = UUID.randomUUID();
        final Map<UUID, List<LedgerEntry>> input = Map.of(
                oldSubject, List.of(entry(oldSubject, now.minus(60, ChronoUnit.DAYS))),
                newSubject, List.of(entry(newSubject, now.minus(5, ChronoUnit.DAYS))));

        final Map<UUID, List<LedgerEntry>> result = RetentionEligibilityChecker.eligibleSubjects(input, now, 30);

        assertThat(result).containsKey(oldSubject);
        assertThat(result).doesNotContainKey(newSubject);
    }
}
