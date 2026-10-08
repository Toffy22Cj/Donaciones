package com.traceability.app.web;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * ADR-048 §7 (condición de Carlos): la relación cuenta ↔ seudónimo solo la lee {@code identity} y nunca sale por la
 * API. Ninguna clase fuera de {@code identity} usa la tabla, y ningún DTO de {@code api} ni de {@code app.web} tiene un
 * campo {@code donorRef} ni {@code donorPseudonym}.
 */
class DonorPseudonymArchitectureTest {

    static final ArchRule ONLY_IDENTITY_READS_THE_TABLE = noClasses().that().resideOutsideOfPackage("identity..")
            .should().dependOnClassesThat().haveFullyQualifiedName(
                    "identity.infrastructure.persistence.mongo.documents.DonorPseudonymDocument")
            .orShould().dependOnClassesThat().haveFullyQualifiedName(
                    "identity.infrastructure.persistence.mongo.repositories.MongoDonorPseudonymAdapter")
            .orShould().dependOnClassesThat().haveFullyQualifiedName(
                    "identity.application.port.out.DonorPseudonymRepositoryPort")
            .because("ADR-048 §7: la tabla cuenta ↔ seudónimo solo la lee identity");

    static final ArchRule NO_DTO_EXPOSES_THE_DONOR = noFields()
            .that().areDeclaredInClassesThat().resideInAnyPackage("com.traceability.api..", "com.traceability.app.web..")
            .should().haveName("donorRef").orShould().haveName("donorPseudonym")
            .because("ADR-048 §7: ni el seudónimo ni el donorRef salen por la API");

    @Test
    void thePseudonymStaysInIdentity_andNoResponseCarriesTheDonor() {
        JavaClasses production = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.traceability", "identity");
        ONLY_IDENTITY_READS_THE_TABLE.check(production);
        NO_DTO_EXPOSES_THE_DONOR.check(production);
    }
}
