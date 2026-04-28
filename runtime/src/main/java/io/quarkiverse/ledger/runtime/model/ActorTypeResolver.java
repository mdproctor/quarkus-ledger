package io.quarkiverse.ledger.runtime.model;

/**
 * Canonical utility for deriving an {@link ActorType} from an actor ID string.
 *
 * <p>
 * The resolution rules, in priority order:
 * <ol>
 * <li>{@code null} or blank → {@link ActorType#SYSTEM} (safe default)</li>
 * <li>{@code "system"} or {@code "system:*"} → {@link ActorType#SYSTEM}</li>
 * <li>{@code "agent:*"} → {@link ActorType#AGENT}</li>
 * <li>Versioned persona format {@code word:word@version} (e.g. {@code "claude:analyst@v1"}) → {@link ActorType#AGENT}</li>
 * <li>Everything else → {@link ActorType#HUMAN}</li>
 * </ol>
 *
 * <p>
 * All consumers that derive {@link ActorType} from an actor ID string must use this class
 * to ensure consistent classification across the casehubio ecosystem.
 */
public final class ActorTypeResolver {

    private ActorTypeResolver() {
    }

    /**
     * Resolves the {@link ActorType} for the given actor ID.
     *
     * @param actorId the actor identifier, may be {@code null}
     * @return the resolved {@link ActorType}, never {@code null}
     */
    public static ActorType resolve(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            return ActorType.SYSTEM;
        }
        if (actorId.equals("system") || actorId.startsWith("system:")) {
            return ActorType.SYSTEM;
        }
        if (actorId.startsWith("agent:")) {
            return ActorType.AGENT;
        }
        // Versioned persona format: word:word@word — e.g. "claude:analyst@v1"
        if (actorId.matches("[\\w-]+:[\\w-]+@[\\w.]+")) {
            return ActorType.AGENT;
        }
        return ActorType.HUMAN;
    }
}
