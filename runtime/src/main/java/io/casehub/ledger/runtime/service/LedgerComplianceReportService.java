package io.casehub.ledger.runtime.service;

import java.time.Instant;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.casehub.ledger.runtime.model.LedgerEntry;
import io.casehub.ledger.runtime.model.supplement.ComplianceSupplement;
import io.casehub.ledger.runtime.model.supplement.ProvenanceSupplement;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;

/**
 * CDI bean providing pre-formatted regulatory query output for GDPR Art.22 and
 * EU AI Act Art.12 compliance reporting.
 *
 * <p>
 * Only {@link LedgerEntry} records that carry a {@link ComplianceSupplement} are included
 * in reports — entries without one represent non-automated or non-reportable decisions.
 *
 * <p>
 * The {@code merkleRootAtGeneration} field provides a tamper-evidence anchor: the current
 * Merkle root for each subject touched by the report, allowing recipients to independently
 * verify that the ledger state has not changed since the report was generated.
 *
 * <p>
 * REST exposure is intentionally omitted — casehub-ledger is an extension that provides
 * services only. Consumers expose these reports via their own REST or MCP endpoints using
 * their own auth model. A config key {@code casehub.ledger.compliance-report.rest-enabled}
 * is reserved for future use.
 */
@ApplicationScoped
public class LedgerComplianceReportService {

    @Inject
    LedgerEntryRepository repo;

    @Inject
    LedgerVerificationService verificationService;

    /**
     * Returns all automated decision records for an actor in a time range.
     *
     * <p>
     * Addresses GDPR Art.22 and EU AI Act Art.12: "show every automated decision
     * made by actor X between dates Y and Z, with tamper-evidence anchors."
     *
     * @param actorId the actor identity
     * @param from    start of the time range (inclusive)
     * @param to      end of the time range (inclusive)
     * @param format  desired output format (controls {@link ComplianceReport#format})
     * @return the compliance report; never null
     */
    @Transactional
    public ComplianceReport reportForActor(
            final String actorId,
            final Instant from,
            final Instant to) {
        final List<LedgerEntry> entries = repo.findByActorId(actorId, from, to);
        final List<DecisionRecord> decisions = entries.stream()
                .filter(e -> e.compliance().isPresent())
                .map(this::toDecisionRecord)
                .toList();

        final String merkleRoot = buildActorMerkleRoot(entries);
        return new ComplianceReport(actorId, null, from, to, decisions.size(), decisions, merkleRoot);
    }

    /**
     * Returns all automated decision records for an aggregate subject in a time range.
     *
     * @param subjectId the aggregate identifier
     * @param from      start of the time range (inclusive)
     * @param to        end of the time range (inclusive)
     * @param format    desired output format (controls {@link ComplianceReport#format})
     * @return the compliance report; never null
     */
    @Transactional
    public ComplianceReport reportForSubject(
            final UUID subjectId,
            final Instant from,
            final Instant to) {
        final List<LedgerEntry> entries = repo.findBySubjectIdAndTimeRange(subjectId, from, to);
        final List<DecisionRecord> decisions = entries.stream()
                .filter(e -> e.compliance().isPresent())
                .map(this::toDecisionRecord)
                .toList();

        final String merkleRoot = resolveSubjectMerkleRoot(subjectId);
        return new ComplianceReport(null, subjectId, from, to, decisions.size(), decisions, merkleRoot);
    }

    private DecisionRecord toDecisionRecord(final LedgerEntry entry) {
        final ComplianceSupplement cs = entry.compliance().orElseThrow();
        final ProvenanceSupplement ps = entry.provenance().orElse(null);
        return new DecisionRecord(
                entry.id,
                entry.occurredAt,
                cs.algorithmRef,
                cs.confidenceScore,
                cs.contestationUri,
                cs.humanOverrideAvailable,
                ps != null ? ps.sourceEntityType : null,
                ps != null ? ps.sourceEntityId : null);
    }

    /**
     * For an actor report, builds a semicolon-separated list of {@code subjectId=merkleRoot}
     * pairs for all distinct subjects referenced in the report entries.
     */
    private String buildActorMerkleRoot(final List<LedgerEntry> entries) {
        final List<UUID> subjectIds = entries.stream()
                .map(e -> e.subjectId)
                .filter(id -> id != null)
                .distinct()
                .toList();

        if (subjectIds.isEmpty()) {
            return null;
        }

        final StringJoiner joiner = new StringJoiner(";");
        for (final UUID subjectId : subjectIds) {
            final String root = resolveSubjectMerkleRoot(subjectId);
            if (root != null) {
                joiner.add(subjectId + "=" + root);
            }
        }
        return joiner.length() > 0 ? joiner.toString() : null;
    }

    private String resolveSubjectMerkleRoot(final UUID subjectId) {
        try {
            return verificationService.treeRoot(subjectId);
        } catch (final Exception e) {
            return null;
        }
    }
}
