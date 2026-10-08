package com.traceability.convocatoria.domain.model;

import com.traceability.convocatoria.domain.exception.CampaignDateInPastException;
import com.traceability.convocatoria.domain.exception.CampaignDescriptionTooLongException;
import com.traceability.convocatoria.domain.exception.CampaignVisibilityRequiredException;
import com.traceability.convocatoria.domain.exception.InvalidCampaignCurrencyException;
import com.traceability.convocatoria.domain.exception.MonetaryTermsWithoutMonetaryDonationTypeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Deudas D-1, D-2, D-7, D-8 y D-9 de la ficha CV-01 (plan B6-a §2.5, tests 5 y 6). */
class CampaignCreationDebtsTest {

    private static ConvocatoriaConfiguration monetaryWithCurrency(String currency) {
        return new ConvocatoriaConfiguration(EnumSet.of(DonationType.MONETARY), Set.of(PaymentMethod.GATEWAY), currency,
                1000L, TargetPolicy.FLEXIBLE, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cop", "CO", "COPX", "XYZ", "C0P", "€"})
    void d1_aCurrencyThatIsNotAnIso4217Code_isRejected(String currency) {
        assertThrows(InvalidCampaignCurrencyException.class, () -> monetaryWithCurrency(currency));
    }

    @ParameterizedTest
    @ValueSource(strings = {"COP", "USD", "EUR"})
    void d1_iso4217Codes_areAccepted(String currency) {
        assertDoesNotThrow(() -> monetaryWithCurrency(currency));
    }

    @Test
    void d7_paymentMethodsInAnInKindOnlyCampaign_areRejected() {
        assertThrows(MonetaryTermsWithoutMonetaryDonationTypeException.class, () -> new ConvocatoriaConfiguration(
                EnumSet.of(DonationType.IN_KIND), Set.of(PaymentMethod.CASH), null, null, null, null));
    }

    @Test
    void d8_visibilityIsRequired() {
        assertThrows(CampaignVisibilityRequiredException.class, () -> Convocatoria.create("c", "o", "p", "Title", null,
                null, DomainFixtures.START, DomainFixtures.END, DomainFixtures.inKindOnly()));
    }

    @Test
    void d9_descriptionUpTo5000Characters() {
        assertDoesNotThrow(() -> Convocatoria.create("c", "o", "p", "Title", "x".repeat(5000), Visibility.PUBLIC,
                DomainFixtures.START, DomainFixtures.END, DomainFixtures.inKindOnly()));
        assertThrows(CampaignDescriptionTooLongException.class, () -> Convocatoria.create("c", "o", "p", "Title",
                "x".repeat(5001), Visibility.PUBLIC, DomainFixtures.START, DomainFixtures.END, DomainFixtures.inKindOnly()));
    }

    @Test
    void d2_datesMayBeUpToFiveMinutesInThePast_notOneSecondMore() {
        Instant now = Instant.parse("2026-10-07T12:00:00Z");
        Instant limit = now.minus(Duration.ofMinutes(5));
        Instant end = now.plus(Duration.ofDays(30));

        assertDoesNotThrow(() -> Convocatoria.requireDatesNotInPast(limit, end, now));
        assertThrows(CampaignDateInPastException.class,
                () -> Convocatoria.requireDatesNotInPast(limit.minusSeconds(1), end, now));
        assertThrows(CampaignDateInPastException.class,
                () -> Convocatoria.requireDatesNotInPast(limit.minusSeconds(120), limit.minusSeconds(1), now));
        assertEquals(Duration.ofMinutes(5), Convocatoria.DATE_TOLERANCE);
    }
}
