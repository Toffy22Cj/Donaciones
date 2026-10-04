package identity.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.traceability.core.archfixture.CoreMarker;
import identity.application.service.CreateAccountService;
import identity.archfixture.HelperThatBuildsActor;
import identity.archfixture.HelperThatBuildsSystemActor;
import identity.archfixture.ViolatesCoreRule;
import identity.domain.archfixture.ViolatesDomainRule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ArchitectureRulesBiteTest {

    @Test
    void identity_should_not_depend_on_core_bites() {
        JavaClasses classes = new ClassFileImporter().importClasses(ViolatesCoreRule.class, CoreMarker.class);
        assertThrows(AssertionError.class, () ->
                ArchitectureTest.identity_should_not_depend_on_core.check(classes)
        );
    }

    @Test
    void only_CreateAccountService_and_AuditLogEntryMapper_may_call_AccountAuditActor_constructor_bites() {
        JavaClasses classes = new ClassFileImporter().importClasses(HelperThatBuildsActor.class);
        assertThrows(AssertionError.class, () ->
                ArchitectureTest.only_CreateAccountService_and_AuditLogEntryMapper_may_call_AccountAuditActor_constructor.check(classes)
        );
    }

    @Test
    void only_BootstrapPlatformAuthorityService_and_AuditLogEntryMapper_may_call_SystemAuditActor_constructor_bites() {
        JavaClasses classes = new ClassFileImporter().importClasses(HelperThatBuildsSystemActor.class);
        assertThrows(AssertionError.class, () ->
                ArchitectureTest.only_BootstrapPlatformAuthorityService_and_AuditLogEntryMapper_may_call_SystemAuditActor_constructor.check(classes)
        );
    }

    @Test
    void domain_should_not_depend_on_application_or_infrastructure_bites() {
        JavaClasses classes = new ClassFileImporter().importClasses(ViolatesDomainRule.class, CreateAccountService.class);
        assertThrows(AssertionError.class, () ->
                ArchitectureTest.domain_should_not_depend_on_application_or_infrastructure.check(classes)
        );
    }
}
