package com.traceability.core.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructor;

@AnalyzeClasses(packages = "com.traceability.core")
public class ArchitectureTest {

    @ArchTest
    static final ArchRule domain_should_not_depend_on_infrastructure =
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..",
                "com.mongodb..",
                "org.bson.."
            )
            .allowEmptyShould(true)
            .because("Domain classes must not depend on Spring or MongoDB as per ADRs.");

    @ArchTest
    static final ArchRule command_services_must_depend_on_authorization_policy =
        classes()
            .that().resideInAPackage("..application.command..")
            .and().haveSimpleNameEndingWith("CommandService")
            .should(new ArchCondition<JavaClass>("inject RoleAuthorizationPolicy in constructor") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    boolean hasIt = false;
                    for (JavaConstructor constructor : item.getConstructors()) {
                        if (constructor.getRawParameterTypes().stream().anyMatch(t -> t.getName().equals(com.traceability.core.application.authorization.RoleAuthorizationPolicy.class.getName()))) {
                            hasIt = true;
                            break;
                        }
                    }
                    if (!hasIt) {
                        events.add(SimpleConditionEvent.violated(item, item.getName() + " does not inject RoleAuthorizationPolicy in its constructor"));
                    }
                }
            })
            .because("All Command Services must inject RoleAuthorizationPolicy to enforce role-based authorization rules (ADR-032).");
}
