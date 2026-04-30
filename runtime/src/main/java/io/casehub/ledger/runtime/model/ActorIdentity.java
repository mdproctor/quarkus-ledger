package io.casehub.ledger.runtime.model;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Token-to-identity mapping for actor pseudonymisation.
 * Plain {@code @Entity} — queries via {@code @NamedQuery} + EntityManager.
 *
 * <p>
 * Each row maps one UUID token (stored in ledger entries) to one real actor identity.
 * Deleting a row severs the link — the token in existing entries becomes unresolvable.
 */
@Entity
@Table(name = "actor_identity")
@NamedQuery(name = "ActorIdentity.findByActorId", query = "SELECT a FROM ActorIdentity a WHERE a.actorId = :actorId")
@NamedQuery(name = "ActorIdentity.deleteByActorId", query = "DELETE FROM ActorIdentity a WHERE a.actorId = :actorId")
public class ActorIdentity extends io.casehub.ledger.api.model.ActorIdentity {

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
