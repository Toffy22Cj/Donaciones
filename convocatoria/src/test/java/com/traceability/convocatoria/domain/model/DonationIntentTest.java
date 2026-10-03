package com.traceability.convocatoria.domain.model;

import com.traceability.convocatoria.domain.exception.CampaignClosedException;
import com.traceability.convocatoria.domain.exception.DonationIntentExpiredException;
import com.traceability.convocatoria.domain.exception.IncompleteConfirmationException;
import com.traceability.convocatoria.domain.exception.InvalidDonationAmountException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.traceability.convocatoria.domain.model.DomainFixtures.convocatoria;
import static com.traceability.convocatoria.domain.model.DomainFixtures.flexibleMonetary;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** implementation_plan.md §3.5, §9: creación, vencimiento y transición {@code PENDING → CONFIRMED}. */
class DonationIntentTest {

    private static final Instant EXPIRES = Instant.parse("2026-10-10T00:00:00Z");

    @Test
    void gatewayIntentIsConfirmedByPaymentProviderAndHasNoExpiration() {
        Convocatoria c = convocatoria(flexibleMonetary());
        DonationIntent intent = DonationIntent.create("i-1", "f-1", c, "donor", 100, "COP", PaymentMethod.GATEWAY, EXPIRES);
        assertEquals(DonationIntentStatus.PENDING, intent.getStatus());
        assertEquals(ConfirmationSource.PAYMENT_PROVIDER, intent.getConfirmationSource());
        assertEquals(PaymentMethod.GATEWAY, intent.getPaymentMethod());
        assertNull(intent.getExpiresAt());
        assertNull(intent.getPaymentSessionId());
        assertNull(intent.getProviderEventId());
        assertEquals("camp-1", intent.getCampaignRef());
        assertEquals("org-1", intent.getOrganizationRef());
        assertEquals("f-1", intent.getFundId());
    }

    @Test
    void bankTransferIntentIsConfirmedByOrganizationAndExpires() {
        Convocatoria c = convocatoria(flexibleMonetary());
        DonationIntent intent = DonationIntent.create("i-1", "f-1", c, "donor", 100, "COP",
                PaymentMethod.BANK_TRANSFER, EXPIRES);
        assertEquals(ConfirmationSource.ORGANIZATION, intent.getConfirmationSource());
        assertEquals(EXPIRES, intent.getExpiresAt());
    }

    @Test
    void recordsConfigurationVersionReadAtCreation() {
        Convocatoria c = convocatoria(flexibleMonetary());
        c.reconfigure(flexibleMonetary());
        DonationIntent intent = DonationIntent.create("i-1", "f-1", c, "donor", 100, "COP", PaymentMethod.GATEWAY, null);
        assertEquals(2L, intent.getConfigurationVersion());
    }

    @Test
    void amountMustBePositive() {
        Convocatoria c = convocatoria(flexibleMonetary());
        assertThrows(InvalidDonationAmountException.class, () -> DonationIntent.create("i", "f", c, "d", 0, "COP",
                PaymentMethod.GATEWAY, null));
    }

    @Test
    void creationChecksConvocatoriaPreconditions() {
        Convocatoria c = convocatoria(flexibleMonetary());
        c.close();
        assertThrows(CampaignClosedException.class, () -> DonationIntent.create("i", "f", c, "d", 10, "COP",
                PaymentMethod.GATEWAY, null));
    }

    @Test
    void confirmAppliesOnceAndRecordsConfirmation() {
        Convocatoria c = convocatoria(flexibleMonetary());
        DonationIntent intent = DonationIntent.create("i", "f", c, "d", 10, "COP", PaymentMethod.BANK_TRANSFER, EXPIRES);
        Instant at = EXPIRES.minusSeconds(60);

        assertTrue(intent.confirm("admin-1", at, "BANK-REF-1"));
        assertEquals(DonationIntentStatus.CONFIRMED, intent.getStatus());
        assertEquals(new DonationIntent.Confirmation("admin-1", at, PaymentMethod.BANK_TRANSFER, "BANK-REF-1"),
                intent.getConfirmation());

        assertFalse(intent.confirm("admin-2", at.plusSeconds(1), "BANK-REF-2"));
        assertEquals("admin-1", intent.getConfirmation().confirmedBy());
    }

    @Test
    void expiredBankTransferCannotBeConfirmed() {
        Convocatoria c = convocatoria(flexibleMonetary());
        DonationIntent intent = DonationIntent.create("i", "f", c, "d", 10, "COP", PaymentMethod.BANK_TRANSFER, EXPIRES);
        assertThrows(DonationIntentExpiredException.class, () -> intent.confirm("admin", EXPIRES, "REF"));
        assertEquals(DonationIntentStatus.PENDING, intent.getStatus());
        assertNull(intent.getConfirmation());
    }

    @Test
    void gatewayIntentNeverExpires() {
        Convocatoria c = convocatoria(flexibleMonetary());
        DonationIntent intent = DonationIntent.create("i", "f", c, "d", 10, "COP", PaymentMethod.GATEWAY, null);
        assertFalse(intent.isExpiredAt(Instant.MAX));
    }

    @Test
    void confirmationRequiresWhoWhenAndReference() {
        Convocatoria c = convocatoria(flexibleMonetary());
        DonationIntent intent = DonationIntent.create("i", "f", c, "d", 10, "COP", PaymentMethod.GATEWAY, null);
        Instant now = Instant.now();
        assertThrows(IncompleteConfirmationException.class, () -> intent.confirm(null, now, "REF"));
        assertThrows(IncompleteConfirmationException.class, () -> intent.confirm("p", null, "REF"));
        assertThrows(IncompleteConfirmationException.class, () -> intent.confirm("p", now, " "));
        assertEquals(DonationIntentStatus.PENDING, intent.getStatus());
    }

    @Test
    void closingConvocatoriaDoesNotInvalidateExistingIntent() {
        Convocatoria c = convocatoria(flexibleMonetary());
        DonationIntent intent = DonationIntent.create("i", "f", c, "d", 10, "COP", PaymentMethod.GATEWAY, null);
        c.close();
        assertTrue(intent.confirm("provider", Instant.now(), "evt-1"));
    }
}
