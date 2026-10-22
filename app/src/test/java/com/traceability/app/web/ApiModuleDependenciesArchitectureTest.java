package com.traceability.app.web;

import com.traceability.api.archfixture.ApiUsingConvocatoriaAndIdentity;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ficha R-6, test 4 (D-R6-1): {@code api} no depende de {@code convocatoria} ni de {@code identity}; los datos de
 * esos módulos le llegan por puertos de {@code contracts} que implementa {@code app}. Se comprueba desde {@code app},
 * el único módulo que ve todas las clases.
 */
class ApiModuleDependenciesArchitectureTest {

    static final ArchRule API_WITHOUT_CONVOCATORIA_NOR_IDENTITY = noClasses()
            .that().resideInAPackage("com.traceability.api..")
            .should().dependOnClassesThat().resideInAnyPackage("com.traceability.convocatoria..", "identity..")
            .because("api solo conoce core y contracts; convocatoria e identity le llegan por puertos (ficha R-6, D-R6-1)");

    @Test
    void api_dependsNeitherOnConvocatoriaNorOnIdentity() {
        JavaClasses production = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.traceability", "identity");
        API_WITHOUT_CONVOCATORIA_NOR_IDENTITY.check(production);
    }

    /** La regla detecta una violación (precedente: reglas "demostradas activas" de B6-0). */
    @Test
    void theRule_detectsBothViolations() {
        JavaClasses fixture = new ClassFileImporter().importClasses(ApiUsingConvocatoriaAndIdentity.class);
        assertThatThrownBy(() -> API_WITHOUT_CONVOCATORIA_NOR_IDENTITY.check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("CampaignTitleQuery")
                .hasMessageContaining("OrganizationPublicNameQuery");
    }
}
