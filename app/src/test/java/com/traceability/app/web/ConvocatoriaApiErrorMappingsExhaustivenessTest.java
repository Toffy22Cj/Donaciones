package com.traceability.app.web;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.traceability.api.web.ApiErrorMapping;
import com.traceability.convocatoria.domain.exception.ConvocatoriaDomainException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan B6-a §2.6, test 11: toda subclase concreta de {@code ConvocatoriaDomainException} está traducida o en la lista
 * explícita de "fuera de B6-a" con su motivo. Una excepción nueva sin decidir rompe el build en vez de acabar en un 500
 * silencioso.
 */
class ConvocatoriaApiErrorMappingsExhaustivenessTest {

    /** Excepciones que ninguna ruta de B6-a puede lanzar, con el bloque que deberá traducirlas. */
    static final Map<String, String> OUT_OF_B6A = Map.ofEntries(
            Map.entry("AssignmentAlreadyRemovedException", "retirar responsable: sin ruta en la demo"),
            Map.entry("CampaignAlreadyClosedException", "cerrar convocatoria: sin ruta en la demo"),
            Map.entry("CampaignAlreadyHasDonationsException", "editar configuración: sin ruta en la demo"),
            Map.entry("CampaignClosedException", "CV-11 (B6-b)"),
            Map.entry("CampaignFundingLimitExceededException", "aplicación de fondos (B6-b)"),
            Map.entry("CashDonationIntentNotSupportedException", "CV-11 (B6-b)"),
            Map.entry("CloseOnTargetCloseNotSupportedException", "CV-11 (B6-b)"),
            Map.entry("ConfigurationChangeOnClosedCampaignException", "editar configuración: sin ruta en la demo"),
            Map.entry("ConfigurationVersionConflictException", "editar configuración: sin ruta en la demo"),
            Map.entry("DonationCurrencyMismatchException", "CV-11 (B6-b)"),
            Map.entry("DonationIntentExpiredException", "confirmación (B6-b)"),
            Map.entry("DonationIntentNotConfirmedException", "aplicación de fondos (B6-b)"),
            Map.entry("DonationIntentNotFoundException", "B6-b"),
            Map.entry("DonationTypeNotAcceptedException", "CV-11 (B6-b)"),
            Map.entry("GatewayIntentManualConfirmationNotAllowedException", "confirmación manual: fuera de la demo"),
            Map.entry("IncompleteConfirmationException", "confirmación (B6-b)"),
            Map.entry("InvalidDonationAmountException", "CV-11 (B6-b)"),
            Map.entry("InvalidFundingAmountException", "aplicación de fondos (B6-b)"),
            Map.entry("LastResponsibleRemovalWithoutReplacementException", "retirar responsable: sin ruta en la demo"),
            Map.entry("MonetaryTermsChangeNotSupportedException", "editar configuración: sin ruta en la demo"),
            Map.entry("PaymentMethodNotAcceptedException", "CV-11 (B6-b)"),
            Map.entry("ReplacementActingRoleRequiredException", "retirar responsable: sin ruta en la demo"),
            Map.entry("ResponsibleAssignmentNotFoundException", "retirar responsable: sin ruta en la demo"));

    @Test
    void everyConcreteConvocatoriaException_isTranslatedOrExplicitlyOutOfB6a() {
        Set<String> concrete = new ClassFileImporter()
                .importPackages("com.traceability.convocatoria.domain.exception").stream()
                .filter(c -> c.isAssignableTo(ConvocatoriaDomainException.class))
                .filter(c -> !c.getModifiers().contains(JavaModifier.ABSTRACT))
                .map(JavaClass::getSimpleName)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> translated = new ConvocatoriaApiErrorMappings().mappings().stream()
                .map(ApiErrorMapping::exception).map(Class::getSimpleName).collect(Collectors.toSet());

        Set<String> undecided = new TreeSet<>(concrete);
        undecided.removeAll(translated);
        undecided.removeAll(OUT_OF_B6A.keySet());

        assertThat(undecided).as("excepciones de convocatoria sin traducción ni motivo").isEmpty();
        assertThat(translated).as("ninguna traducida está además en la lista de fuera").doesNotContainAnyElementsOf(OUT_OF_B6A.keySet());
        assertThat(translated).doesNotContain("ConvocatoriaDomainException");
        assertThat(concrete).containsAll(OUT_OF_B6A.keySet());
    }
}
