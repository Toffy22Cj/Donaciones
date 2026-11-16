package com.traceability.app.bootstrap.historic;

import com.traceability.app.archfixture.SimulatedTimeOutsideTheHistoricSeed;
import com.traceability.core.domain.shared.EventTime;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan del paso 2 (2026-11-16): fechar hechos en el pasado es justo lo que un sistema de trazabilidad no debe permitir
 * en producción. Solo la semilla histórica de {@code app} puede armar la fuente de tiempo o usar el bloque fechado; y
 * esa semilla solo existe con el perfil {@code demo-historico}, que fuera de {@code dev} impide arrancar.
 */
class SimulatedTimeArchitectureTest {

    static final String SEED_PACKAGE = "com.traceability.app.bootstrap.historic..";

    static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.traceability", "identity");

    static final ArchRule ONLY_THE_HISTORIC_SEED_BACKDATES = noClasses()
            .that().resideOutsideOfPackage(SEED_PACKAGE)
            .and().doNotHaveFullyQualifiedName(EventTime.class.getName())
            .should().callMethod(EventTime.class, "callAt", java.time.Instant.class, java.util.function.Supplier.class)
            .orShould().callMethod(EventTime.class, "arm")
            .because("solo la semilla histórica (perfil demo-historico) puede fechar hechos en el pasado");

    static final ArchRule THE_HISTORIC_SEED_ONLY_EXISTS_WITH_ITS_PROFILE = classes()
            .that().resideInAPackage(SEED_PACKAGE)
            .and(com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates
                    .annotatedWith(org.springframework.stereotype.Component.class)
                    .or(com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates
                            .annotatedWith(org.springframework.context.annotation.Configuration.class)))
            .should().beAnnotatedWith(Profile.class)
            .because("sin el perfil demo-historico no deben existir ni el armado, ni el reloj, ni la semilla");

    @Test
    void nobodyOutsideTheHistoricSeed_armsOrUsesTheSimulatedTime() {
        ONLY_THE_HISTORIC_SEED_BACKDATES.check(PRODUCTION);
    }

    @Test
    void theRule_detectsAClassThatBackdatesOrArms_outsideTheSeed() {
        assertThatThrownBy(() -> ONLY_THE_HISTORIC_SEED_BACKDATES.check(
                new ClassFileImporter().importClasses(SimulatedTimeOutsideTheHistoricSeed.class)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("callAt")
                .hasMessageContaining("arm()");
    }

    @Test
    void theRule_looksAtRealCallers_theSeedAndItsConfigurationAreTheOnlyOnes() {
        JavaClasses seed = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.traceability.app.bootstrap.historic");
        // Si la regla no viera las llamadas reales, pasaría también sin la excepción del paquete de la semilla
        ArchRule withoutTheException = noClasses()
                .should().callMethod(EventTime.class, "callAt", java.time.Instant.class, java.util.function.Supplier.class)
                .orShould().callMethod(EventTime.class, "arm");
        assertThatThrownBy(() -> withoutTheException.check(seed)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("DemoHistoricSeeder").hasMessageContaining("DemoHistoricConfig");
    }

    @Test
    void everyBeanOfTheHistoricSeed_isBoundToItsProfile() {
        THE_HISTORIC_SEED_ONLY_EXISTS_WITH_ITS_PROFILE.check(PRODUCTION);
        for (Class<?> bean : new Class<?>[] {DemoHistoricConfig.class, DemoHistoricSeeder.class,
                DemoHistoricSeedRunner.class}) {
            assertThat(bean.getAnnotation(Profile.class).value()).as(bean.getSimpleName())
                    .containsExactly("demo-historico");
        }
    }

    @Test
    void outsideDev_theProfileDoesNotStart() {
        Environment production = new MockEnvironment().withProperty("spring.profiles.active", "demo-historico");
        ((MockEnvironment) production).setActiveProfiles("demo-historico");

        assertThatThrownBy(() -> new DemoHistoricConfig(production))
                .isInstanceOf(DemoHistoricProfileNotAllowedException.class).hasMessageContaining("dev");
        assertThat(EventTime.isArmed()).as("no llegó a armarse").isFalse();
    }

    @Test
    void withDev_theProfileStarts_andItsArmingLastsOnlyUntilItIsClosed() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev", "demo-historico");
        DemoHistoricConfig config = new DemoHistoricConfig(dev);
        assertThat(EventTime.isArmed()).as("construir la configuración no arma nada").isFalse();

        EventTime.Armed armed = config.demoHistoricEventTime();
        try {
            assertThat(EventTime.isArmed()).isTrue();
        } finally {
            armed.close();
        }
        assertThat(EventTime.isArmed()).isFalse();
        assertThatCode(() -> config.demoHistoricClock().instant()).doesNotThrowAnyException();
    }
}
