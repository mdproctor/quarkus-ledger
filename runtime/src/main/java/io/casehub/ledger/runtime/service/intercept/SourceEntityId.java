package io.casehub.ledger.runtime.service.intercept;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method parameter as the source entity identifier for {@link ProvenanceCapture}.
 *
 * <p>
 * When present, {@link ProvenanceCaptureInterceptor} uses this parameter's
 * {@code toString()} value as {@code ProvenanceSupplement.sourceEntityId}.
 * If absent, the interceptor falls back to the first {@link java.util.UUID} parameter.
 *
 * <pre>{@code
 * @ProvenanceCapture(sourceEntityType = "WorkItem")
 * void complete(@SourceEntityId UUID workItemId, String reason) { ... }
 * }</pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface SourceEntityId {
}
