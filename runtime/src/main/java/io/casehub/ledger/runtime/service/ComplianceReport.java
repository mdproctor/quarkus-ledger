package io.casehub.ledger.runtime.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Pre-formatted regulatory output from {@link LedgerComplianceReportService}.
 *
 * <p>
 * Contains the structured decision records and a Merkle-root tamper-evidence anchor
 * at the time the report was generated. Use {@link #format(ReportFormat)} to produce
 * a formatted string for submission.
 *
 * @param actorId                 the actor whose decisions are covered; null for subject reports
 * @param subjectId               the aggregate whose decisions are covered; null for actor reports
 * @param from                    start of the query window (inclusive)
 * @param to                      end of the query window (inclusive)
 * @param totalDecisions          number of entries with a ComplianceSupplement in the window
 * @param decisions               one {@link DecisionRecord} per qualifying ledger entry
 * @param merkleRootAtGeneration  tamper-evidence anchor at the time of report generation;
 *                                for subject reports: the subject's current Merkle root;
 *                                for actor reports: semicolon-separated {@code subjectId=root} pairs
 */
public record ComplianceReport(
        String actorId,
        UUID subjectId,
        Instant from,
        Instant to,
        int totalDecisions,
        List<DecisionRecord> decisions,
        String merkleRootAtGeneration) {

    /**
     * Format this report as a string in the requested format.
     *
     * @param format the output format; {@link ReportFormat#PLAIN_JSON} returns a JSON object,
     *               {@link ReportFormat#JSON_LD} wraps it with PROV-DM context,
     *               {@link ReportFormat#CSV} returns RFC 4180 CSV
     */
    public String format(final ReportFormat format) {
        return switch (format) {
            case PLAIN_JSON -> toPlainJson();
            case JSON_LD -> toJsonLd();
            case CSV -> toCsv();
        };
    }

    private String toPlainJson() {
        final StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"actorId\": ").append(jsonStr(actorId)).append(",\n");
        sb.append("  \"subjectId\": ").append(jsonStr(subjectId != null ? subjectId.toString() : null)).append(",\n");
        sb.append("  \"from\": ").append(jsonStr(from.toString())).append(",\n");
        sb.append("  \"to\": ").append(jsonStr(to.toString())).append(",\n");
        sb.append("  \"totalDecisions\": ").append(totalDecisions).append(",\n");
        sb.append("  \"merkleRootAtGeneration\": ").append(jsonStr(merkleRootAtGeneration)).append(",\n");
        sb.append("  \"decisions\": [\n");
        for (int i = 0; i < decisions.size(); i++) {
            sb.append(decisionToJson(decisions.get(i)));
            if (i < decisions.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("  ]\n}");
        return sb.toString();
    }

    private String toJsonLd() {
        final String base = toPlainJson();
        // JSON-LD envelope with PROV-DM @context. This is a non-normative framing —
        // it associates the report with the W3C PROV context URI but does not claim full
        // PROV-DM conformance. Consumers requiring normative PROV-DM output should use the
        // LedgerProvExportService JSON-LD export instead.
        return "{\n" +
               "  \"@context\": \"https://www.w3.org/ns/prov\",\n" +
               "  \"@type\": \"ComplianceReport\",\n" +
               "  \"report\": " + base.replace("\n", "\n  ") + "\n}";
    }

    private String toCsv() {
        final StringBuilder sb = new StringBuilder();
        sb.append("entryId,occurredAt,algorithmRef,confidenceScore,contestationUri," +
                  "humanOverrideAvailable,sourceEntityType,sourceEntityId\n");
        for (final DecisionRecord d : decisions) {
            sb.append(csvField(d.entryId() != null ? d.entryId().toString() : "")).append(',');
            sb.append(csvField(d.occurredAt() != null ? d.occurredAt().toString() : "")).append(',');
            sb.append(csvField(d.algorithmRef())).append(',');
            sb.append(d.confidenceScore() != null ? d.confidenceScore() : "").append(',');
            sb.append(csvField(d.contestationUri())).append(',');
            sb.append(d.humanOverrideAvailable() != null ? d.humanOverrideAvailable() : "").append(',');
            sb.append(csvField(d.sourceEntityType())).append(',');
            sb.append(csvField(d.sourceEntityId())).append('\n');
        }
        return sb.toString();
    }

    private static String decisionToJson(final DecisionRecord d) {
        return "    {" +
               "\"entryId\": " + jsonStr(d.entryId() != null ? d.entryId().toString() : null) +
               ", \"occurredAt\": " + jsonStr(d.occurredAt() != null ? d.occurredAt().toString() : null) +
               ", \"algorithmRef\": " + jsonStr(d.algorithmRef()) +
               ", \"confidenceScore\": " + d.confidenceScore() +
               ", \"contestationUri\": " + jsonStr(d.contestationUri()) +
               ", \"humanOverrideAvailable\": " + d.humanOverrideAvailable() +
               ", \"sourceEntityType\": " + jsonStr(d.sourceEntityType()) +
               ", \"sourceEntityId\": " + jsonStr(d.sourceEntityId()) +
               "}";
    }

    private static String jsonStr(final String value) {
        if (value == null) {
            return "null";
        }
        // Backslash must be escaped first, before other replacements produce backslashes
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }

    private static String csvField(final String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
