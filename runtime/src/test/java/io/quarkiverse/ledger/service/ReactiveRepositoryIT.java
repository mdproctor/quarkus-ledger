package io.quarkiverse.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import org.junit.jupiter.api.Test;

import io.quarkiverse.ledger.runtime.repository.ReactiveLedgerEntryRepository;
import io.quarkiverse.ledger.runtime.repository.jpa.ReactiveJpaLedgerEntryRepository;

/**
 * Structural verification of {@link ReactiveJpaLedgerEntryRepository}.
 *
 * <p>
 * A {@code @QuarkusTest} is not used here because Hibernate Reactive requires a Vert.x-based
 * reactive datasource — incompatible with the H2 JDBC datasource used by the existing test suite.
 * End-to-end reactive integration tests require a reactive datasource (e.g. Reactive PG) and
 * {@code quarkus-test-vertx}, both of which are not yet configured in this module.
 *
 * <p>
 * These structural tests confirm that the class is correctly annotated and implements
 * the full {@link ReactiveLedgerEntryRepository} SPI without requiring a running Quarkus context.
 */
class ReactiveRepositoryIT {

    @Test
    void reactiveRepository_implementsReactiveSpi() {
        assertThat(ReactiveLedgerEntryRepository.class)
                .isAssignableFrom(ReactiveJpaLedgerEntryRepository.class);
    }

    @Test
    void reactiveRepository_isAnnotatedAlternative() {
        assertThat(ReactiveJpaLedgerEntryRepository.class.isAnnotationPresent(Alternative.class))
                .as("@Alternative must be present so the bean is inactive by default")
                .isTrue();
    }

    @Test
    void reactiveRepository_isAnnotatedApplicationScoped() {
        assertThat(ReactiveJpaLedgerEntryRepository.class.isAnnotationPresent(ApplicationScoped.class))
                .as("@ApplicationScoped must be present")
                .isTrue();
    }

    @Test
    void reactiveRepository_implementsAllSpiMethods() {
        final Set<String> spiMethodNames = Arrays.stream(ReactiveLedgerEntryRepository.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        final Set<String> implMethodNames = Arrays.stream(ReactiveJpaLedgerEntryRepository.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertThat(implMethodNames)
                .as("ReactiveJpaLedgerEntryRepository must implement all SPI methods")
                .containsAll(spiMethodNames);
    }
}
