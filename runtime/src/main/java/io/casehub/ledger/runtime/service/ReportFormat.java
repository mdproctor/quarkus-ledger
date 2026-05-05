package io.casehub.ledger.runtime.service;

/**
 * Output format for {@link ComplianceReport#format(ReportFormat)}.
 */
public enum ReportFormat {
    /**
     * Plain JSON — the structured {@link ComplianceReport} serialised as a JSON object.
     * Suitable for programmatic consumption and API responses.
     */
    PLAIN_JSON,

    /**
     * JSON-LD with W3C PROV-DM context.
     * Suitable for linked-data regulatory submissions (e.g. EU AI Act Art.12).
     */
    JSON_LD,

    /**
     * RFC 4180 CSV — one row per decision, header row included.
     * Suitable for spreadsheet import and FCA / EU AI Act tabular submissions.
     */
    CSV
}
