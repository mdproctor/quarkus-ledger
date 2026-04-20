package io.quarkiverse.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkiverse.ledger.runtime.model.ActorTrustScore;
import io.quarkiverse.ledger.runtime.model.LedgerAttestation;
import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkiverse.ledger.runtime.model.LedgerEntryArchiveRecord;
import io.quarkiverse.ledger.runtime.model.LedgerMerkleFrontier;
import io.quarkiverse.ledger.runtime.model.supplement.ComplianceSupplement;
import io.quarkiverse.ledger.runtime.model.supplement.LedgerSupplement;
import io.quarkiverse.ledger.runtime.model.supplement.ProvenanceSupplement;

/**
 * Structural tests ensuring all entities are plain @Entity POJOs.
 * Fails if any entity re-introduces PanacheEntityBase — prevents regression.
 */
class PlainEntityTest {

    private static final List<Class<?>> ALL_ENTITIES = List.of(
            LedgerEntry.class,
            LedgerMerkleFrontier.class,
            LedgerAttestation.class,
            ActorTrustScore.class,
            LedgerEntryArchiveRecord.class,
            LedgerSupplement.class,
            ComplianceSupplement.class,
            ProvenanceSupplement.class);

    @Test
    void allEntities_doNotExtendPanacheEntityBase() {
        for (Class<?> entity : ALL_ENTITIES) {
            boolean extendsPanache = false;
            Class<?> c = entity.getSuperclass();
            while (c != null && c != Object.class) {
                if (c.getName().contains("PanacheEntityBase")) {
                    extendsPanache = true;
                    break;
                }
                c = c.getSuperclass();
            }
            assertThat(extendsPanache)
                    .as(entity.getSimpleName() + " must not extend PanacheEntityBase")
                    .isFalse();
        }
    }

    @Test
    void allEntities_haveEntityAnnotation() {
        for (Class<?> entity : ALL_ENTITIES) {
            assertThat(entity.isAnnotationPresent(jakarta.persistence.Entity.class)
                    || entity.isAnnotationPresent(jakarta.persistence.MappedSuperclass.class))
                    .as(entity.getSimpleName() + " must have @Entity or @MappedSuperclass")
                    .isTrue();
        }
    }
}
