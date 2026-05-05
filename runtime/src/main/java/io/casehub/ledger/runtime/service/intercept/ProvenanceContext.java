package io.casehub.ledger.runtime.service.intercept;

import java.util.ArrayDeque;
import java.util.Deque;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Thread-local stack holding the active provenance source state for the current
 * {@link ProvenanceCapture} interceptor scope.
 *
 * <p>
 * {@code ApplicationScoped} with a static {@code ThreadLocal<Deque>} is used rather than
 * {@code @RequestScoped} so the context is available in scheduled jobs, async workers,
 * and {@code @QuarkusTest} without requiring an active HTTP request scope.
 *
 * <p>
 * The stack supports nesting: each {@link ProvenanceCaptureInterceptor} call pushes on entry
 * and pops in a {@code finally} block, so the inner-most intercepted call's context always wins.
 * The {@code ThreadLocal} is removed automatically when the stack becomes empty, preventing
 * classloader leaks across Quarkus hot reloads.
 *
 * <p>
 * Not thread-safe across threads — each thread maintains its own stack. This is intentional:
 * provenance context is scoped to the execution thread handling a single business operation.
 */
@ApplicationScoped
public class ProvenanceContext {

    /** Immutable capture of the source entity coordinates set by one interceptor frame. */
    public record SourceState(String entityType, String entityId, String entitySystem) {
    }

    // Static ThreadLocal so each thread has its own nesting stack.
    // Removed when the stack empties to prevent classloader leaks on hot reload.
    private static final ThreadLocal<Deque<SourceState>> STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * Push a new provenance frame onto the current thread's stack.
     * Called by the interceptor before {@code ic.proceed()}.
     */
    public void push(final String entityType, final String entityId, final String entitySystem) {
        STACK.get().push(new SourceState(entityType, entityId, entitySystem));
    }

    /**
     * Pop the top provenance frame from the current thread's stack.
     * Called by the interceptor in its {@code finally} block.
     * Removes the {@code ThreadLocal} when the stack becomes empty.
     */
    public void pop() {
        final Deque<SourceState> stack = STACK.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        if (stack.isEmpty()) {
            STACK.remove();
        }
    }

    /**
     * Returns true when at least one {@link ProvenanceCapture} interceptor frame is active
     * on the current thread. {@link ProvenanceCaptureEnricher} uses this to decide whether
     * to attach a supplement.
     */
    public boolean isActive() {
        return !STACK.get().isEmpty();
    }

    /**
     * Returns the current (inner-most) provenance state, or {@code null} if not active.
     */
    public SourceState current() {
        return STACK.get().peek();
    }
}
