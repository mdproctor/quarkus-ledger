package io.casehub.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.runtime.repository.LedgerEntryRepository;
import io.casehub.ledger.runtime.repository.ReactiveLedgerEntryRepository;

/**
 * Structural verification of the reactive SPI.
 *
 * <p>
 * No {@code @QuarkusTest} — Hibernate Reactive requires a Vert.x-based reactive datasource
 * incompatible with the H2 JDBC pool used by the existing test suite.
 *
 * <p>
 * {@link ReactiveLedgerEntryRepository} provides the SPI contract. Consumers implement it
 * using {@code ReactivePanacheRepository<LedgerEntry, UUID>} from
 * {@code quarkus-hibernate-reactive-panache} in their own module.
 */
class ReactiveRepositoryIT {

    @Test
    void reactiveSpi_usesUniReturnTypes() {
        long uniMethods = Arrays.stream(ReactiveLedgerEntryRepository.class.getDeclaredMethods())
                .filter(m -> m.getReturnType().getName().contains("Uni"))
                .count();
        assertThat(uniMethods)
                .as("All SPI methods must return Uni<T>")
                .isEqualTo(ReactiveLedgerEntryRepository.class.getDeclaredMethods().length);
    }

    @Test
    void reactiveSpi_coversAllBlockingSpiMethods() {
        Set<String> blockingNames = Arrays.stream(LedgerEntryRepository.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
        Set<String> reactiveNames = Arrays.stream(ReactiveLedgerEntryRepository.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
        // findAllEvents is a batch concern excluded from the reactive SPI
        blockingNames.remove("findAllEvents");
        assertThat(reactiveNames)
                .as("ReactiveLedgerEntryRepository must cover all LedgerEntryRepository methods")
                .containsAll(blockingNames);
    }
}
