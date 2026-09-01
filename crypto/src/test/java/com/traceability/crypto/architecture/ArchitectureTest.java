package com.traceability.crypto.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.traceability.crypto")
public class ArchitectureTest {

    @ArchTest
    static final ArchRule crypto_should_not_depend_on_core =
        noClasses()
            .that().resideInAPackage("com.traceability.crypto..")
            .should().dependOnClassesThat().resideInAPackage("com.traceability.core..")
            .allowEmptyShould(true)
            .because("Crypto module must not depend on core module as per ADRs.");
}
