package io.quarkiverse.ledger.runtime.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;

import io.quarkiverse.ledger.runtime.config.LedgerConfig;
import io.quarkus.hibernate.orm.PersistenceUnit;

/**
 * Unit tests for {@link LedgerEntityManagerProducer}.
 *
 * Uses concrete stubs instead of Mockito to match the project's existing test style.
 * Same package as the producer so the package-private {@code config} field is accessible.
 */
class LedgerEntityManagerProducerTest {

    // ── Stubs ─────────────────────────────────────────────────────────────────

    /** Records which qualifier was passed to select(). */
    private static final class CapturingInstance implements Instance<EntityManager> {

        private final EntityManager returnValue;
        Annotation capturedQualifier;

        CapturingInstance(final EntityManager returnValue) {
            this.returnValue = returnValue;
        }

        @Override
        public Instance<EntityManager> select(final Annotation... qualifiers) {
            if (qualifiers.length > 0) {
                capturedQualifier = qualifiers[0];
            }
            return this;
        }

        @Override
        public <U extends EntityManager> Instance<U> select(final Class<U> subtype, final Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends EntityManager> Instance<U> select(final TypeLiteral<U> subtype, final Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EntityManager get() {
            return returnValue;
        }

        @Override
        public boolean isUnsatisfied() {
            return false;
        }

        @Override
        public boolean isAmbiguous() {
            return false;
        }

        @Override
        public void destroy(final EntityManager instance) {
        }

        @Override
        public Handle<EntityManager> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Handle<EntityManager>> handles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<EntityManager> iterator() {
            return List.of(returnValue).iterator();
        }
    }

    private static LedgerConfig configWith(final String datasource) {
        return new StubLedgerConfig(Optional.ofNullable(datasource));
    }

    private static LedgerConfig configWithEmpty() {
        return new StubLedgerConfig(Optional.empty());
    }

    /** Minimal stub that only implements {@code datasource()}. */
    private record StubLedgerConfig(Optional<String> datasource) implements LedgerConfig {

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public HashChainConfig hashChain() {
            throw new UnsupportedOperationException();
        }

        @Override
        public DecisionContextConfig decisionContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public EvidenceConfig evidence() {
            throw new UnsupportedOperationException();
        }

        @Override
        public AttestationConfig attestations() {
            throw new UnsupportedOperationException();
        }

        @Override
        public TrustScoreConfig trustScore() {
            throw new UnsupportedOperationException();
        }

        @Override
        public RetentionConfig retention() {
            throw new UnsupportedOperationException();
        }

        @Override
        public MerkleConfig merkle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public IdentityConfig identity() {
            throw new UnsupportedOperationException();
        }
    }

    private LedgerEntityManagerProducer producerWith(final LedgerConfig config) {
        final LedgerEntityManagerProducer producer = new LedgerEntityManagerProducer();
        // Same package — package-private field accessible here
        producer.config = config;
        return producer;
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void produce_noDatasourceConfig_selectsDefault() {
        final EntityManager em = dummyEm();
        final CapturingInstance instance = new CapturingInstance(em);
        final LedgerEntityManagerProducer producer = producerWith(configWithEmpty());

        final EntityManager result = producer.produce(instance);

        assertThat(result).isSameAs(em);
        assertThat(instance.capturedQualifier.annotationType()).isEqualTo(Default.class);
    }

    @Test
    void produce_emptyDatasourceConfig_selectsDefault() {
        final EntityManager em = dummyEm();
        final CapturingInstance instance = new CapturingInstance(em);
        final LedgerEntityManagerProducer producer = producerWith(configWith(""));

        final EntityManager result = producer.produce(instance);

        assertThat(result).isSameAs(em);
        assertThat(instance.capturedQualifier.annotationType()).isEqualTo(Default.class);
    }

    @Test
    void produce_namedDatasource_selectsByPersistenceUnitName() {
        final EntityManager em = dummyEm();
        final CapturingInstance instance = new CapturingInstance(em);
        final LedgerEntityManagerProducer producer = producerWith(configWith("qhorus"));

        final EntityManager result = producer.produce(instance);

        assertThat(result).isSameAs(em);
        assertThat(instance.capturedQualifier.annotationType()).isEqualTo(PersistenceUnit.class);
        assertThat(((PersistenceUnit) instance.capturedQualifier).value()).isEqualTo("qhorus");
    }

    @Test
    void produce_blankDatasource_selectsDefault() {
        final EntityManager em = dummyEm();
        final CapturingInstance instance = new CapturingInstance(em);
        final LedgerEntityManagerProducer producer = producerWith(configWith("   "));

        final EntityManager result = producer.produce(instance);

        assertThat(result).isSameAs(em);
        assertThat(instance.capturedQualifier.annotationType()).isEqualTo(Default.class);
    }

    private static EntityManager dummyEm() {
        // Return null — we only check isSameAs identity, and the stub returns it directly.
        // Using null avoids a heavyweight JPA proxy dependency in a pure unit test.
        return null;
    }
}
