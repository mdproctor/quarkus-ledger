package io.casehub.ledger.api.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

/**
 * Token-to-identity mapping for actor pseudonymisation.
 *
 * <p>
 * Each row maps one UUID token (stored in ledger entries) to one real actor identity.
 * Deleting a row severs the link — the token in existing entries becomes unresolvable.
 */
@MappedSuperclass
public class ActorIdentity {

    /** UUID token stored in ledger entries in place of the real identity. */
    @Id
    @Column(name = "token")
    public String token;

    /** The real actor identity this token represents. */
    @Column(name = "actor_id", nullable = false)
    public String actorId;

    /** When this mapping was created. */
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
