package io.casehub.ledger.runtime.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import io.casehub.ledger.runtime.config.LedgerConfig;
import io.quarkus.hibernate.orm.PersistenceUnit;

/**
 * CDI producer for the {@link LedgerPersistenceUnit}-qualified {@link EntityManager}.
 *
 * <p>
 * Selects the default persistence unit when {@code casehub.ledger.datasource} is
 * empty (the common case), or a named persistence unit when a datasource name is
 * configured. This allows quarkus-ledger to work in deployments that do not configure
 * a default datasource — e.g. applications with only named datasources.
 */
@ApplicationScoped
public class LedgerEntityManagerProducer {

    @Inject
    LedgerConfig config;

    @Produces
    @LedgerPersistenceUnit
    public EntityManager produce(@Any final Instance<EntityManager> instance) {
        final String datasource = config.datasource().orElse("").trim();
        if (datasource.isBlank()) {
            return instance.select(Default.Literal.INSTANCE).get();
        }
        return instance.select(new PersistenceUnitLiteral(datasource)).get();
    }

    /** AnnotationLiteral for {@link io.quarkus.hibernate.orm.PersistenceUnit}. */
    @SuppressWarnings("ClassExplicitlyAnnotation")
    private static final class PersistenceUnitLiteral
            extends AnnotationLiteral<PersistenceUnit> implements PersistenceUnit {

        private final String value;

        PersistenceUnitLiteral(final String value) {
            this.value = value;
        }

        @Override
        public String value() {
            return value;
        }
    }
}
