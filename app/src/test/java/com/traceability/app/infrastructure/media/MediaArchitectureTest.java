package com.traceability.app.infrastructure.media;

import com.traceability.app.application.archfixture.ApplicationUsingInfrastructure;
import com.traceability.app.application.archfixture.ApplicationUsingSpringData;
import com.traceability.app.archfixture.AwsSdkOutsideTheMediaAdapter;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-051 F1 y F9: el SDK de AWS solo en {@code app} (y dentro de {@code app}, solo en
 * {@code infrastructure.media}); {@code contracts} sin dependencias más allá de Java; y el único cliente HTTP del SDK
 * es el de la JDK (sin Netty ni Apache, P-3); y la capa de aplicación de {@code app} no conoce su infraestructura ni
 * Spring Data (iteración DT-2 del plan: los metadatos se usan a través del puerto {@code MediaObjectStore}).
 */
class MediaArchitectureTest {

    static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.traceability", "identity");

    static final ArchRule AWS_SDK_ONLY_IN_INFRASTRUCTURE_MEDIA = noClasses()
            .that().resideOutsideOfPackage("com.traceability.app.infrastructure.media..")
            .should().dependOnClassesThat().resideInAPackage("software.amazon.awssdk..")
            .because("el cliente S3 es una dependencia solo de app y vive en su adaptador (ADR-051 F1)");

    static final ArchRule CONTRACTS_ONLY_JAVA = classes()
            .that().resideInAPackage("com.traceability.contracts..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "com.traceability.contracts..")
            .because("contracts no tiene dependencias de infraestructura; MediaStoragePort solo usa tipos de Java (ADR-051 F9)");

    static final ArchRule APPLICATION_WITHOUT_INFRASTRUCTURE = noClasses()
            .that().resideInAPackage("com.traceability.app.application..")
            .should().dependOnClassesThat().resideInAnyPackage("com.traceability.app.infrastructure..",
                    "org.springframework.data..")
            .because("la capa de aplicación de app usa puertos de salida, como application.port.out en los demás módulos"
                    + " (ADR-051 F9; plan de la Tarea 1, DT-2)");

    @Test
    void appApplication_doesNotDependOnAppInfrastructureNorSpringData() {
        APPLICATION_WITHOUT_INFRASTRUCTURE.check(PRODUCTION);
    }

    @Test
    void theApplicationLayerRule_detectsBothViolations() {
        assertThatThrownBy(() -> APPLICATION_WITHOUT_INFRASTRUCTURE.check(
                new ClassFileImporter().importClasses(ApplicationUsingInfrastructure.class)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("MediaObjectRepository");
        assertThatThrownBy(() -> APPLICATION_WITHOUT_INFRASTRUCTURE.check(
                new ClassFileImporter().importClasses(ApplicationUsingSpringData.class)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("org.springframework.data");
    }

    @Test
    void awsSdk_onlyInAppInfrastructureMedia() {
        AWS_SDK_ONLY_IN_INFRASTRUCTURE_MEDIA.check(PRODUCTION);
    }

    @Test
    void contracts_dependsOnlyOnJava() {
        CONTRACTS_ONLY_JAVA.check(PRODUCTION);
    }

    @Test
    void theMediaPortAndItsException_areInContracts() {
        assertThat(PRODUCTION.contain("com.traceability.contracts.media.MediaStoragePort")).isTrue();
        assertThat(PRODUCTION.contain("com.traceability.contracts.media.MediaStorageUnavailableException")).isTrue();
    }

    /** La regla del SDK detecta una violación (precedente: reglas "demostradas activas" de B6-0). */
    @Test
    void theAwsSdkRule_detectsAViolation() {
        JavaClasses fixture = new ClassFileImporter().importClasses(AwsSdkOutsideTheMediaAdapter.class);
        assertThatThrownBy(() -> AWS_SDK_ONLY_IN_INFRASTRUCTURE_MEDIA.check(fixture)).isInstanceOf(AssertionError.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient",
            "software.amazon.awssdk.http.apache.ApacheHttpClient",
            "software.amazon.awssdk.http.apache5.Apache5HttpClient"})
    void nettyAndApacheHttpClients_areNotOnTheClasspath(String className) {
        assertThatThrownBy(() -> Class.forName(className)).isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void theJdkHttpClient_isOnTheClasspath() {
        assertThatCode(() -> Class.forName("software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient"))
                .doesNotThrowAnyException();
    }
}
