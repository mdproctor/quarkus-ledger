package io.casehub.ledger.runtime.service.intercept;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;

/**
 * CDI interceptor binding that automatically attaches a {@link io.casehub.ledger.runtime.model.supplement.ProvenanceSupplement}
 * to any {@link io.casehub.ledger.runtime.model.LedgerEntry} persisted during the annotated method's execution.
 *
 * <p>
 * The interceptor resolves {@code sourceEntityId} from a {@link SourceEntityId}-annotated parameter
 * first; if none is present, it falls back to the first {@link java.util.UUID} parameter.
 * {@code sourceEntityType} and {@code sourceEntitySystem} are taken from the annotation attributes.
 *
 * <pre>{@code
 * @ProvenanceCapture(sourceEntityType = "WorkItem", sourceEntitySystem = "casehub-work")
 * public void completeWorkItem(@SourceEntityId UUID workItemId, String resolution) {
 *     // Any LedgerEntry persisted here automatically gets
 *     // ProvenanceSupplement{sourceEntityId=workItemId, sourceEntityType="WorkItem", ...}
 *     ledgerEntryRepository.save(entry);
 * }
 * }</pre>
 *
 * <p>
 * Nesting is supported: the inner-most intercepted call's context wins. Context is always
 * cleared in a {@code finally} block — exceptions do not leak provenance state.
 */
@InterceptorBinding
@Inherited
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ProvenanceCapture {

    /**
     * The type of the source entity. Example: {@code "WorkItem"}, {@code "Flow:WorkflowInstance"}.
     * When empty, no {@code sourceEntityType} is set on the supplement.
     */
    @Nonbinding
    String sourceEntityType() default "";

    /**
     * The system that owns the source entity. Example: {@code "casehub-work"}, {@code "quarkus-flow"}.
     * When empty, no {@code sourceEntitySystem} is set on the supplement.
     */
    @Nonbinding
    String sourceEntitySystem() default "";
}
