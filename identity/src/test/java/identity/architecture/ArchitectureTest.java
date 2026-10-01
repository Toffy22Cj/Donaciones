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

    @ArchTest
    public static final ArchRule application_should_not_call_AuditLogEntry_constructors =
            noClasses().that().resideInAPackage("identity.application..")
                    .should().callConstructorWhere(com.tngtech.archunit.base.DescribedPredicate.describe(
                            "target is AuditLogEntry constructor",
                            call -> call.getTargetOwner().isEquivalentTo(identity.domain.model.AuditLogEntry.class)))
                    .because("Application services must use AuditLogEntry factory methods (ADR-038 §2.2)");

    @ArchTest
    public static final ArchRule identity_should_not_depend_on_core =
            noClasses().that().resideInAPackage("identity..")
                    .should().dependOnClassesThat().resideInAPackage("com.traceability.core..")
                    .because("Identity module must not depend on core (ADR-038 §2.2)");

    @ArchTest
    public static final ArchRule only_AuditLogEntryMapper_may_call_AuditLogEntry_legacy =
            noClasses().that().doNotHaveFullyQualifiedName(identity.infrastructure.persistence.mongo.mappers.AuditLogEntryMapper.class.getName())
                    .should().callMethodWhere(com.tngtech.archunit.base.DescribedPredicate.describe(
                            "target is AuditLogEntry.legacy",
                            call -> call.getTargetOwner().isEquivalentTo(identity.domain.model.AuditLogEntry.class) && call.getName().equals("legacy")))
                    .because("Only AuditLogEntryMapper may construct legacy PRE_CUTOVER entries (ADR-038 §2.2)");

    @ArchTest
    public static final ArchRule only_CreateAccountService_and_AuditLogEntryMapper_may_call_AccountAuditActor_constructor =
            noClasses().that().doNotHaveFullyQualifiedName(identity.application.service.CreateAccountService.class.getName())
                    .and().doNotHaveFullyQualifiedName(identity.infrastructure.persistence.mongo.mappers.AuditLogEntryMapper.class.getName())
                    .and().doNotHaveFullyQualifiedName(identity.domain.model.AuditActor.AccountAuditActor.class.getName())
                    .should().callConstructorWhere(com.tngtech.archunit.base.DescribedPredicate.describe(
                            "target is AccountAuditActor constructor",
                            call -> call.getTargetOwner().isEquivalentTo(identity.domain.model.AuditActor.AccountAuditActor.class)))
                    .because("Only CreateAccountService (self-registration) and AuditLogEntryMapper may construct AccountAuditActor (D3 / ADR-038 §2.2)");
}

