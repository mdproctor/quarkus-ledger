package io.casehub.ledger.runtime.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.casehub.ledger.runtime.model.LedgerEntry;

/**
 * Pure Java utility that identifies ledger subjects eligible for retention archival.
 *
 * <p>
 * A subject is eligible only when <strong>every</strong> entry in its chain is older
 * than the configured retention window. This all-or-nothing rule preserves Merkle chain
 * integrity: archiving and deleting a partial chain would break the inclusion proofs
 * for the entries that remain.
 *
 * <p>
 * No CDI, no database — all inputs are passed as collections. Safe for unit testing.
 */
public final class RetentionEligibilityChecker {

    private RetentionEligibilityChecker() {
    }

    /**
     * Return the subset of subjects where every entry has an {@code occurredAt}
     * timestamp at or before the retention cutoff ({@code now - operationalDays}).
     *
     * @param allBySubject all ledger entries grouped by {@code subjectId}
     * @param now reference timestamp for age calculation
     * @param operationalDays retention window in days (EU AI Act Art.12 minimum: 180)
     * @return map of eligible subjects; never null, may be empty
     */
    public static Map<UUID, List<LedgerEntry>> eligibleSubjects(
            final Map<UUID, List<LedgerEntry>> allBySubject,
            final Instant now,
            final int operationalDays) {

        final Instant cutoff = now.minus(operationalDays, ChronoUnit.DAYS);
        final Map<UUID, List<LedgerEntry>> eligible = new LinkedHashMap<>();

        for (final Map.Entry<UUID, List<LedgerEntry>> e : allBySubject.entrySet()) {
            final List<LedgerEntry> entries = e.getValue();
            if (entries.isEmpty()) {
                continue;
            }
            final boolean allOldEnough = entries.stream()
                    .allMatch(entry -> entry.occurredAt != null
                            && !entry.occurredAt.isAfter(cutoff));
            if (allOldEnough) {
                eligible.put(e.getKey(), entries);
            }
        }

        return eligible;
    }
}
