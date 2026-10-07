package com.traceability.api.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.traceability.api.auth.jwt.JwtAuthFilter;
import com.traceability.api.web.CurrentActor;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan B6-0 §2.1 (test 9): ningún controlador lee el principal ni la cabecera {@code Authorization} directamente;
 * siempre usa {@link CurrentActor}. El mismo par de reglas se aplica a {@code app.web} en el módulo {@code app}.
 */
class ControllerPrincipalAccessTest {

    static final DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass> CONTROLLERS =
            DescribedPredicate.describe("controllers",
                    c -> c.isAnnotatedWith(RestController.class) || c.isAnnotatedWith(Controller.class));

    static final ArchRule NO_RAW_REQUEST = noClasses().that(CONTROLLERS)
            .should().dependOnClassesThat().areAssignableTo(ServletRequest.class)
            .orShould().dependOnClassesThat().areAssignableTo(WebRequest.class)
            .because("el principal y la cabecera Authorization solo se leen con @CurrentActor (plan B6-0 §2.1)");

    static final ArchRule NO_PRINCIPAL_ATTRIBUTE_NOR_AUTHORIZATION_HEADER = methods()
            .that().areDeclaredInClassesThat(CONTROLLERS)
            .should(new ArchCondition<JavaMethod>("not bind the principal attribute nor the Authorization header") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    for (JavaParameter parameter : method.getParameters()) {
                        for (JavaAnnotation<JavaParameter> annotation : parameter.getAnnotations()) {
                            boolean attribute = annotation.getRawType().isEquivalentTo(RequestAttribute.class)
                                    && names(annotation).stream().anyMatch(JwtAuthFilter.PRINCIPAL_ATTRIBUTE::equals);
                            boolean header = annotation.getRawType().isEquivalentTo(RequestHeader.class)
                                    && names(annotation).stream().anyMatch("Authorization"::equalsIgnoreCase);
                            if (attribute || header) {
                                events.add(SimpleConditionEvent.violated(method,
                                        method.getFullName() + " reads the principal directly; use @CurrentActor"));
                            }
                        }
                    }
                }
            })
            .allowEmptyShould(true);

    static java.util.List<String> names(JavaAnnotation<?> annotation) {
        return java.util.stream.Stream.of("value", "name")
                .map(annotation::get).flatMap(java.util.Optional::stream).map(String::valueOf).toList();
    }

    @Test
    void productionControllers_neverReadThePrincipalDirectly() {
        JavaClasses production = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.traceability.api");
        NO_RAW_REQUEST.check(production);
        NO_PRINCIPAL_ATTRIBUTE_NOR_AUTHORIZATION_HEADER.check(production);
    }

    // --- las reglas detectan lo que deben (controladores solo de test) ---

    @RestController
    static class ReadsTheAttribute {
        @GetMapping("/x")
        String x(@RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) Object principal) {
            return "x";
        }
    }

    @RestController
    static class ReadsTheHeader {
        @GetMapping("/x")
        String x(@RequestHeader("authorization") String header) {
            return header;
        }
    }

    @RestController
    static class ReadsTheRequest {
        @GetMapping("/x")
        String x(HttpServletRequest request) {
            return String.valueOf(request.getAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE));
        }
    }

    @Test
    void theRules_rejectEachWayOfReadingThePrincipalDirectly() {
        assertThatThrownBy(() -> NO_PRINCIPAL_ATTRIBUTE_NOR_AUTHORIZATION_HEADER.check(
                new ClassFileImporter().importClasses(ReadsTheAttribute.class))).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> NO_PRINCIPAL_ATTRIBUTE_NOR_AUTHORIZATION_HEADER.check(
                new ClassFileImporter().importClasses(ReadsTheHeader.class))).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> NO_RAW_REQUEST.check(
                new ClassFileImporter().importClasses(ReadsTheRequest.class))).isInstanceOf(AssertionError.class);
    }
}
