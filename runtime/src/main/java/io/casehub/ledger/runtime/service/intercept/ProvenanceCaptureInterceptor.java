package io.casehub.ledger.runtime.service.intercept;

import java.lang.reflect.Parameter;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * CDI interceptor that captures provenance context before a {@link ProvenanceCapture}-annotated
 * method executes, making it available to {@link ProvenanceCaptureEnricher} during any
 * {@link io.casehub.ledger.runtime.model.LedgerEntry} persist that occurs within the call.
 *
 * <p>
 * The {@code sourceEntityId} is resolved in priority order:
 * <ol>
 * <li>The first method parameter annotated with {@link SourceEntityId} (explicit binding)</li>
 * <li>The first {@link UUID} parameter (implicit convention)</li>
 * <li>{@code null} if neither is present</li>
 * </ol>
 *
 * <p>
 * The annotation is read from the method first, then the declaring class — allowing type-level
 * defaults overridden per method.
 *
 * <p>
 * Context is always cleared in a {@code finally} block — exceptions cannot leak provenance
 * state into subsequent calls on the same thread.
 */
@Interceptor
@ProvenanceCapture
@Priority(Interceptor.Priority.APPLICATION)
public class ProvenanceCaptureInterceptor {

    @Inject
    ProvenanceContext context;

    @AroundInvoke
    public Object capture(final InvocationContext ic) throws Exception {
        final ProvenanceCapture annotation = resolveAnnotation(ic);
        final String entityType = annotation != null ? annotation.sourceEntityType() : "";
        final String entitySystem = annotation != null ? annotation.sourceEntitySystem() : "";
        final String entityId = resolveEntityId(ic);

        context.push(entityType, entityId, entitySystem);
        try {
            return ic.proceed();
        } finally {
            context.pop();
        }
    }

    private static ProvenanceCapture resolveAnnotation(final InvocationContext ic) {
        final ProvenanceCapture method = ic.getMethod().getAnnotation(ProvenanceCapture.class);
        if (method != null) {
            return method;
        }
        // Fall back to type-level annotation. Use getDeclaringClass() not getTarget().getClass()
        // because getTarget() returns the CDI proxy subclass, which does not carry annotations.
        return ic.getMethod().getDeclaringClass().getAnnotation(ProvenanceCapture.class);
    }

    private static String resolveEntityId(final InvocationContext ic) {
        final Parameter[] params = ic.getMethod().getParameters();
        final Object[] args = ic.getParameters();

        // Priority 1: @SourceEntityId annotated parameter
        for (int i = 0; i < params.length; i++) {
            if (params[i].isAnnotationPresent(SourceEntityId.class)) {
                return args[i] != null ? args[i].toString() : null;
            }
        }

        // Priority 2: first UUID parameter
        for (int i = 0; i < params.length; i++) {
            if (args[i] instanceof UUID) {
                return args[i].toString();
            }
        }

        return null;
    }
}
