package com.traceability.api.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;

@AnalyzeClasses(packages = "com.traceability.api", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    @ArchTest
    static final ArchRule api_should_not_depend_on_core_infrastructure =
        noClasses()
            .that().resideInAPackage("com.traceability.api..")
            .should().dependOnClassesThat().resideInAPackage("com.traceability.core.infrastructure..")
            .allowEmptyShould(true)
            .because("API module must only interact with core application ports, never with core infrastructure (ADR-020).");
}
