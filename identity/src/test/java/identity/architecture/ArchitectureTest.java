package identity.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

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

    @ArchTest
    static final ArchRule no_classes_in_application_should_be_annotated_with_transactional =
            noClasses().that().resideInAPackage("identity.application..")
                    .should().beAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
                    .because("Application classes must not use @Transactional (ADR-026 / ADR-038)");

    @ArchTest
    static final ArchRule no_methods_in_application_should_be_annotated_with_transactional =
            noMethods().that().areDeclaredInClassesThat().resideInAPackage("identity.application..")
                    .should().beAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
                    .because("Application methods must not use @Transactional (ADR-026 / ADR-038)");

    @ArchTest
    static final ArchRule services_must_depend_on_mongo_transaction_retry_helper =
            classes().that().resideInAPackage("identity.application.service..")
                    .and().haveSimpleNameEndingWith("Service")
                    .should().dependOnClassesThat().haveFullyQualifiedName(identity.application.service.MongoTransactionRetryHelper.class.getName())
                    .because("All identity application services must use MongoTransactionRetryHelper (ADR-026 / ADR-038)");
}

