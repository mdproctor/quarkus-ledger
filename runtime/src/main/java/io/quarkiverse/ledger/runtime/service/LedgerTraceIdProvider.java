package io.quarkiverse.ledger.runtime.service;

import java.util.Optional;

/**
 * SPI for supplying the current distributed trace ID to the ledger.
 *
 * <p>
 * The default implementation ({@link OtelTraceIdProvider}) reads from the active
 * OpenTelemetry span context. Replace this bean to integrate with a different
 * tracing system or to override the trace ID in tests.
 */
public interface LedgerTraceIdProvider {

    /**
     * Returns the current trace ID, or empty if no active trace is present.
     *
     * @return the trace ID (W3C 32-char hex format when using OTel), or empty
     */
    Optional<String> currentTraceId();
}
