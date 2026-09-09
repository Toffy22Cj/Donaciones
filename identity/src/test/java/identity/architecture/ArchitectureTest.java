package identity.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "identity", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    @ArchTest
    static final ArchRule domain_should_not_depend_on_spring_or_mongo =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "com.mongodb..");

    @ArchTest
    static final ArchRule identity_should_not_depend_on_core_infrastructure =
            noClasses().that().resideInAPackage("identity..")
                    .should().dependOnClassesThat().resideInAPackage("com.traceability.core.infrastructure..");
}
