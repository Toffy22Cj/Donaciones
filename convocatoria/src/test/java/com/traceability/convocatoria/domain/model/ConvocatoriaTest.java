package com.traceability.convocatoria.domain.model;

import com.traceability.convocatoria.domain.exception.CampaignAlreadyClosedException;
import com.traceability.convocatoria.domain.exception.CampaignClosedException;
import com.traceability.convocatoria.domain.exception.CampaignTitleRequiredException;
import com.traceability.convocatoria.domain.exception.CampaignTitleTooLongException;
import com.traceability.convocatoria.domain.exception.CashDonationIntentNotSupportedException;
import com.traceability.convocatoria.domain.exception.ConfigurationChangeOnClosedCampaignException;
import com.traceability.convocatoria.domain.exception.DonationCurrencyMismatchException;
import com.traceability.convocatoria.domain.exception.DonationTypeNotAcceptedException;
import com.traceability.convocatoria.domain.exception.EmptyAcceptedDonationTypesException;
import com.traceability.convocatoria.domain.exception.IncompleteMonetaryConfigurationException;
import com.traceability.convocatoria.domain.exception.InvalidCampaignDateRangeException;
import com.traceability.convocatoria.domain.exception.InvalidOnTargetReachedException;
import com.traceability.convocatoria.domain.exception.InvalidTargetAmountException;
import com.traceability.convocatoria.domain.exception.MissingCampaignCurrencyException;
import com.traceability.convocatoria.domain.exception.MonetaryTermsChangeNotSupportedException;
import com.traceability.convocatoria.domain.exception.MonetaryTermsWithoutMonetaryDonationTypeException;
import com.traceability.convocatoria.domain.exception.PaymentMethodNotAcceptedException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import static com.traceability.convocatoria.domain.model.DomainFixtures.END;
import static com.traceability.convocatoria.domain.model.DomainFixtures.START;
import static com.traceability.convocatoria.domain.model.DomainFixtures.convocatoria;
import static com.traceability.convocatoria.domain.model.DomainFixtures.flexibleMonetary;
import static com.traceability.convocatoria.domain.model.DomainFixtures.inKindOnly;
import static com.traceability.convocatoria.domain.model.DomainFixtures.monetary;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** implementation_plan.md §3.1: un caso por invariante, positivo y negativo. */
class ConvocatoriaTest {

    // --- Creación (R1) ---

    @Test
    void createsOpenConvocatoriaWithInitialVersion() {
        Convocatoria c = convocatoria(flexibleMonetary());
        assertEquals(ConvocatoriaStatus.OPEN, c.getStatus());
        assertEquals(1L, c.getConfigurationVersion());
    }

    @Test
    void titleIsRequired() {
        assertThrows(CampaignTitleRequiredException.class, () -> Convocatoria.create("c", "o", "p", "  ", null,
                Visibility.PUBLIC, START, END, flexibleMonetary()));
        assertThrows(CampaignTitleRequiredException.class, () -> Convocatoria.create("c", "o", "p", null, null,
                Visibility.PUBLIC, START, END, flexibleMonetary()));
    }

    @Test
    void titleHasMaximumLength() {
        String max = "x".repeat(Convocatoria.TITLE_MAX_LENGTH);
        assertDoesNotThrow(() -> Convocatoria.create("c", "o", "p", max, null, Visibility.PUBLIC, START, END,
                flexibleMonetary()));
        assertThrows(CampaignTitleTooLongException.class, () -> Convocatoria.create("c", "o", "p", max + "x", null,
                Visibility.PUBLIC, START, END, flexibleMonetary()));
    }

    @Test
    void descriptionIsOptional() {
        assertDoesNotThrow(() -> Convocatoria.create("c", "o", "p", "t", null, Visibility.PRIVATE_LINK, START, END,
                inKindOnly()));
    }

    @Test
    void startDateMustBeBeforeEndDate() {
        assertThrows(InvalidCampaignDateRangeException.class, () -> Convocatoria.create("c", "o", "p", "t", null,
                Visibility.PUBLIC, END, START, flexibleMonetary()));
        assertThrows(InvalidCampaignDateRangeException.class, () -> Convocatoria.create("c", "o", "p", "t", null,
                Visibility.PUBLIC, START, START, flexibleMonetary()));
        assertThrows(InvalidCampaignDateRangeException.class, () -> Convocatoria.create("c", "o", "p", "t", null,
                Visibility.PUBLIC, null, END, flexibleMonetary()));
        assertThrows(InvalidCampaignDateRangeException.class, () -> Convocatoria.create("c", "o", "p", "t", null,
                Visibility.PUBLIC, START, null, flexibleMonetary()));
    }

    // --- Configuración (N1, N2, §6.8.4, R1) ---

    @Test
    void acceptedDonationTypesNeverEmpty() {
        assertThrows(EmptyAcceptedDonationTypesException.class, () -> new ConvocatoriaConfiguration(
                EnumSet.noneOf(DonationType.class), null, null, null, null, null));
        assertThrows(EmptyAcceptedDonationTypesException.class, () -> new ConvocatoriaConfiguration(
                null, null, null, null, null, null));
    }

    @Test
    void monetaryRequiresPaymentMethodsTargetAndPolicy() {
        assertThrows(IncompleteMonetaryConfigurationException.class, () -> new ConvocatoriaConfiguration(
                EnumSet.of(DonationType.MONETARY), Set.of(), "COP", 1000L, TargetPolicy.FLEXIBLE, null));
        assertThrows(IncompleteMonetaryConfigurationException.class, () -> new ConvocatoriaConfiguration(
                EnumSet.of(DonationType.MONETARY), Set.of(PaymentMethod.GATEWAY), "COP", null,
                TargetPolicy.FLEXIBLE, null));
        assertThrows(IncompleteMonetaryConfigurationException.class, () -> new ConvocatoriaConfiguration(
                EnumSet.of(DonationType.MONETARY), Set.of(PaymentMethod.GATEWAY), "COP", 1000L, null, null));
        assertDoesNotThrow(() -> new ConvocatoriaConfiguration(EnumSet.of(DonationType.MONETARY,
                DonationType.IN_KIND), Set.of(PaymentMethod.GATEWAY), "COP", 1000L, TargetPolicy.STRICT, null));
    }

    @Test
    void monetaryRequiresCurrency() {
        assertThrows(MissingCampaignCurrencyException.class, () -> new ConvocatoriaConfiguration(
                EnumSet.of(DonationType.MONETARY), Set.of(PaymentMethod.GATEWAY), null, 1000L,
                TargetPolicy.FLEXIBLE, null));
        assertThrows(MissingCampaignCurrencyException.class, () -> new ConvocatoriaConfiguration(
                EnumSet.of(DonationType.MONETARY), Set.of(PaymentMethod.GATEWAY), " ", 1000L,
                TargetPolicy.FLEXIBLE, null));
    }

    @Test
    void targetAmountMustBePositive() {
        assertThrows(InvalidTargetAmountException.class, () -> new ConvocatoriaConfiguration(
                EnumSet.of(DonationType.MONETARY), Set.of(PaymentMethod.GATEWAY), "COP", 0L,
                TargetPolicy.FLEXIBLE, null));
    }

    @Test
    void inKindOnlyHasNoMonetaryTerms() {
        assertDoesNotThrow(DomainFixtures::inKindOnly);
        assertThrows(MonetaryTermsWithoutMonetaryDonationTypeException.class, () -> new ConvocatoriaConfiguration(
                EnumSet.of(DonationType.IN_KIND), null, null, 1000L, null, null));
        assertThrows(MonetaryTermsWithoutMonetaryDonationTypeException.class, () -> new ConvocatoriaConfiguration(
                EnumSet.of(DonationType.IN_KIND), null, null, null, TargetPolicy.FLEXIBLE, null));
        assertThrows(MonetaryTermsWithoutMonetaryDonationTypeException.class, () -> new ConvocatoriaConfiguration(
                EnumSet.of(DonationType.IN_KIND), null, "COP", null, null, null));
        assertThrows(MonetaryTermsWithoutMonetaryDonationTypeException.class, () -> new ConvocatoriaConfiguration(
                EnumSet.of(DonationType.IN_KIND), null, null, null, null, OnTargetReached.CLOSE));
    }

    @Test
    void onTargetReachedPresentIffCloseOnTarget() {
        assertDoesNotThrow(() -> monetary(TargetPolicy.CLOSE_ON_TARGET, OnTargetReached.REJECT_EXCESS,
                PaymentMethod.GATEWAY));
        assertThrows(InvalidOnTargetReachedException.class, () -> monetary(TargetPolicy.CLOSE_ON_TARGET, null,
                PaymentMethod.GATEWAY));
        assertThrows(InvalidOnTargetReachedException.class, () -> monetary(TargetPolicy.STRICT,
                OnTargetReached.ACCEPT_EXCESS, PaymentMethod.GATEWAY));
    }

    // --- Edición de configuración (Enmienda §3.2) ---

    @Test
    void reconfigureIncrementsVersion() {
        Convocatoria c = convocatoria(flexibleMonetary());
        Convocatoria.MonetaryTransition transition = c.reconfigure(monetary(TargetPolicy.FLEXIBLE, null,
                PaymentMethod.GATEWAY));
        assertEquals(Convocatoria.MonetaryTransition.UNCHANGED, transition);
        assertEquals(2L, c.getConfigurationVersion());
        assertEquals(Set.of(PaymentMethod.GATEWAY), c.getConfiguration().acceptedPaymentMethods());
    }

    @Test
    void addingMonetaryIsSingleVersionTransition() {
        Convocatoria c = convocatoria(inKindOnly());
        ConvocatoriaConfiguration withMoney = new ConvocatoriaConfiguration(EnumSet.of(DonationType.IN_KIND,
                DonationType.MONETARY), Set.of(PaymentMethod.BANK_TRANSFER), "COP", 500L, TargetPolicy.STRICT, null);
        assertEquals(Convocatoria.MonetaryTransition.ADDED, c.reconfigure(withMoney));
        assertEquals(2L, c.getConfigurationVersion());
        assertEquals("COP", c.getConfiguration().currency());
    }

    @Test
    void removingMonetaryIsReported() {
        Convocatoria c = convocatoria(new ConvocatoriaConfiguration(EnumSet.of(DonationType.IN_KIND,
                DonationType.MONETARY), Set.of(PaymentMethod.GATEWAY), "COP", 500L, TargetPolicy.STRICT, null));
        assertEquals(Convocatoria.MonetaryTransition.REMOVED, c.reconfigure(inKindOnly()));
    }

    @Test
    void monetaryTermsCannotChange() {
        Convocatoria c = convocatoria(flexibleMonetary());
        assertThrows(MonetaryTermsChangeNotSupportedException.class, () -> c.reconfigure(monetary(
                TargetPolicy.STRICT, null, PaymentMethod.GATEWAY)));
        assertThrows(MonetaryTermsChangeNotSupportedException.class, () -> c.reconfigure(new ConvocatoriaConfiguration(
                EnumSet.of(DonationType.MONETARY), Set.of(PaymentMethod.GATEWAY), "USD", 1000L,
                TargetPolicy.FLEXIBLE, null)));
        assertThrows(MonetaryTermsChangeNotSupportedException.class, () -> c.reconfigure(new ConvocatoriaConfiguration(
                EnumSet.of(DonationType.MONETARY), Set.of(PaymentMethod.GATEWAY), "COP", 2000L,
                TargetPolicy.FLEXIBLE, null)));
        assertEquals(1L, c.getConfigurationVersion());
    }

    @Test
    void closedConvocatoriaRejectsConfigurationChange() {
        Convocatoria c = convocatoria(flexibleMonetary());
        c.close();
        assertThrows(ConfigurationChangeOnClosedCampaignException.class, () -> c.reconfigure(flexibleMonetary()));
        assertEquals(1L, c.getConfigurationVersion());
    }

    // --- Cierre (Enmienda §3.4) ---

    @Test
    void closeTransitionsOpenToClosedOnlyOnce() {
        Convocatoria c = convocatoria(flexibleMonetary());
        c.close();
        assertEquals(ConvocatoriaStatus.CLOSED, c.getStatus());
        assertThrows(CampaignAlreadyClosedException.class, c::close);
        assertEquals(ConvocatoriaStatus.CLOSED, c.getStatus());
    }

    // --- Precondiciones de DonationIntent (ADR-037 §2.6; N6; R1; P5) ---

    @Test
    void acceptsGatewayAndBankTransferInMatchingCurrency() {
        Convocatoria c = convocatoria(flexibleMonetary());
        assertDoesNotThrow(() -> c.assertAcceptsDonationIntent(PaymentMethod.GATEWAY, "COP"));
        assertDoesNotThrow(() -> c.assertAcceptsDonationIntent(PaymentMethod.BANK_TRANSFER, "COP"));
    }

    @Test
    void closedConvocatoriaRejectsNewIntents() {
        Convocatoria c = convocatoria(flexibleMonetary());
        c.close();
        assertThrows(CampaignClosedException.class, () -> c.assertAcceptsDonationIntent(PaymentMethod.GATEWAY, "COP"));
    }

    @Test
    void inKindOnlyRejectsMonetaryIntent() {
        Convocatoria c = convocatoria(inKindOnly());
        assertThrows(DonationTypeNotAcceptedException.class,
                () -> c.assertAcceptsDonationIntent(PaymentMethod.GATEWAY, "COP"));
    }

    @Test
    void paymentMethodMustBeAccepted() {
        Convocatoria c = convocatoria(monetary(TargetPolicy.FLEXIBLE, null, PaymentMethod.GATEWAY));
        assertThrows(PaymentMethodNotAcceptedException.class,
                () -> c.assertAcceptsDonationIntent(PaymentMethod.BANK_TRANSFER, "COP"));
    }

    @Test
    void cashIsNotSupportedInThisCut() {
        Convocatoria c = convocatoria(flexibleMonetary());
        assertThrows(CashDonationIntentNotSupportedException.class,
                () -> c.assertAcceptsDonationIntent(PaymentMethod.CASH, "COP"));
    }

    @Test
    void currencyMustMatch() {
        Convocatoria c = convocatoria(flexibleMonetary());
        assertThrows(DonationCurrencyMismatchException.class,
                () -> c.assertAcceptsDonationIntent(PaymentMethod.GATEWAY, "USD"));
    }

    @Test
    void datesDoNotLimitIntentCreationInThisCut() {
        Convocatoria c = Convocatoria.create("c", "o", "p", "t", null, Visibility.PUBLIC,
                Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2020-02-01T00:00:00Z"), flexibleMonetary());
        assertDoesNotThrow(() -> c.assertAcceptsDonationIntent(PaymentMethod.GATEWAY, "COP"));
    }
}
