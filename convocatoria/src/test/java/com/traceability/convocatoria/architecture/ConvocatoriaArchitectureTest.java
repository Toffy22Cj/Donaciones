package com.traceability.convocatoria.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * implementation_plan.md §12.1 — frontera del módulo {@code convocatoria}.
 * ADR-037 §2: {@code convocatoria → contracts} es su única dependencia directa;
 * B-1 (§11): la dirección {@code convocatoria → app/core/identity} está prohibida siempre.
 */
@AnalyzeClasses(packages = "com.traceability.convocatoria", importOptions = ImportOption.DoNotIncludeTests.class)
public class ConvocatoriaArchitectureTest {

    @ArchTest
    static final ArchRule convocatoria_should_not_depend_on_other_project_modules =
            noClasses().that().resideInAPackage("com.traceability.convocatoria..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.traceability.core..",
                            "identity..",
                            "com.traceability.app..",
                            "com.traceability.api..",
                            "com.traceability.crypto..",
                            "com.traceability.ai..")
                    .allowEmptyShould(true)
                    .because("ADR-037 §2: convocatoria → contracts es su única dependencia directa.");

    @ArchTest
    static final ArchRule domain_should_not_depend_on_spring_or_mongo =
            noClasses().that().resideInAPackage("com.traceability.convocatoria.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..",
                            "com.mongodb..",
                            "org.bson..")
                    .allowEmptyShould(true)
                    .because("implementation_plan.md §3: el dominio no importa Spring ni MongoDB (mismo criterio que core).");
}
