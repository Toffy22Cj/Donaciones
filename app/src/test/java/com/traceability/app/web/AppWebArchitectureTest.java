package com.traceability.app.web;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
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
import jakarta.servlet.ServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan B6-0 §2.1 y §2.4 (test 9) para los controladores que cruzan módulos ({@code com.traceability.app.web}):
 * solo llaman a casos de uso, nunca a repositorios ni a infraestructura, y leen el actor solo con
 * {@code @CurrentActor}. Hoy el paquete está vacío; las reglas fallan contra los controladores de prueba de abajo.
 */
class AppWebArchitectureTest {

    static final String WEB = "com.traceability.app.web..";

    static final DescribedPredicate<JavaClass> CONTROLLERS = DescribedPredicate.describe("controllers",
            c -> c.isAnnotatedWith(RestController.class) || c.isAnnotatedWith(Controller.class));

    static final ArchRule NO_INFRASTRUCTURE = noClasses().that().resideInAPackage(WEB)
            .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..", "..persistence..",
                    "org.springframework.data..", "com.mongodb..")
            .because("los controladores de app.web solo llaman a casos de uso (plan B6-0 §2.4)")
            .allowEmptyShould(true);

    static final ArchRule NO_RAW_REQUEST = noClasses().that().resideInAPackage(WEB).and(CONTROLLERS)
            .should().dependOnClassesThat().areAssignableTo(ServletRequest.class)
            .orShould().dependOnClassesThat().areAssignableTo(WebRequest.class)
            .because("el principal y la cabecera Authorization solo se leen con @CurrentActor (plan B6-0 §2.1)")
            .allowEmptyShould(true);

    static final ArchRule NO_PRINCIPAL_ATTRIBUTE_NOR_AUTHORIZATION_HEADER = methods()
            .that().areDeclaredInClassesThat().resideInAPackage(WEB)
            .and().areDeclaredInClassesThat(CONTROLLERS)
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

    static List<String> names(JavaAnnotation<?> annotation) {
        return Stream.of("value", "name").map(annotation::get).flatMap(Optional::stream).map(String::valueOf).toList();
    }

    @Test
    void productionWebControllers_onlyUseUseCases_andCurrentActor() {
        JavaClasses production = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.traceability.app");
        NO_INFRASTRUCTURE.check(production);
        NO_RAW_REQUEST.check(production);
        NO_PRINCIPAL_ATTRIBUTE_NOR_AUTHORIZATION_HEADER.check(production);
    }

    // --- las reglas detectan lo que deben (controladores solo de test, en este paquete) ---

    @RestController
    static class UsesMongoTemplate {
        private final MongoTemplate mongo;

        UsesMongoTemplate(MongoTemplate mongo) {
            this.mongo = mongo;
        }

        @GetMapping("/x")
        String x() {
            return mongo.getDb().getName();
        }
    }

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
        String x(@RequestHeader(name = "Authorization") String header) {
            return header;
        }
    }

    @RestController
    static class ReadsTheRequest {
        @GetMapping("/x")
        String x(jakarta.servlet.http.HttpServletRequest request) {
            return request.getHeader("Authorization");
        }
    }

    @Test
    void theRules_rejectInfrastructure_andEachWayOfReadingThePrincipalDirectly() {
        assertThatThrownBy(() -> NO_INFRASTRUCTURE.check(new ClassFileImporter().importClasses(UsesMongoTemplate.class)))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> NO_PRINCIPAL_ATTRIBUTE_NOR_AUTHORIZATION_HEADER.check(
                new ClassFileImporter().importClasses(ReadsTheAttribute.class))).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> NO_PRINCIPAL_ATTRIBUTE_NOR_AUTHORIZATION_HEADER.check(
                new ClassFileImporter().importClasses(ReadsTheHeader.class))).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> NO_RAW_REQUEST.check(new ClassFileImporter().importClasses(ReadsTheRequest.class)))
                .isInstanceOf(AssertionError.class);
    }
}
