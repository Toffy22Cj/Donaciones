package identity.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.traceability.core.archfixture.CoreMarker;
import identity.archfixture.HelperThatBuildsActor;
import identity.archfixture.ViolatesCoreRule;
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
}
