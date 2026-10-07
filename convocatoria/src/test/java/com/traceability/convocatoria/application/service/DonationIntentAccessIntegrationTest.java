package com.traceability.convocatoria.application.service;

import com.traceability.convocatoria.application.command.CreateDonationIntentCommand;
import com.traceability.convocatoria.application.command.CreateDonationIntentWithAccessResult;
import com.traceability.convocatoria.application.port.out.PaymentProviderPort;
import com.traceability.convocatoria.application.query.DonationIntentReadPort;
import com.traceability.convocatoria.domain.exception.PaymentEventMismatchException;
import com.traceability.convocatoria.domain.exception.SimulatedPaymentsNotAllowedException;
import com.traceability.convocatoria.domain.model.Convocatoria;
import com.traceability.convocatoria.domain.model.DonationIntent;
import com.traceability.convocatoria.domain.model.PaymentMethod;
import com.traceability.convocatoria.domain.model.PaymentProviders;
import com.traceability.convocatoria.domain.model.StatusTokens;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.DonationIntentDocument;
import com.traceability.convocatoria.support.AbstractConvocatoriaServiceIntegrationTest;
import com.traceability.convocatoria.support.ConvocatoriaScenarios;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

/**
 * Enmienda 3 de ADR-037 en {@code convocatoria} (plan B6-b): sesión del proveedor al crear, segunda barrera de
 * {@code SIMULATED} (E3-Q1), {@code statusToken} (solo su hash, 24 h) y correlación del evento del proveedor. Sin la
 * propiedad {@code traceability.demo.simulated-payments}, como en producción.
 */
class DonationIntentAccessIntegrationTest extends AbstractConvocatoriaServiceIntegrationTest {

    private static final String PROVIDER = "TEST_PROVIDER";
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    @Autowired private ConvocatoriaLifecycleService lifecycle;
    @Autowired private DonationIntentService service;
    @Autowired private GatewayPaymentService gatewayPayments;
    @Autowired private DonationIntentReadPort reads;
    @MockBean private PaymentProviderPort provider;

    private Convocatoria campaign;

    @BeforeEach
    void campaign() {
        clock.set(NOW);
        String ref = ConvocatoriaScenarios.createConvocatoria(lifecycle, ADMIN, ORG, flexible());
        campaign = convocatorias.findByCampaignRef(ref).orElseThrow();
        when(provider.createSession(anyString(), anyLong(), anyString())).thenAnswer(inv ->
                new PaymentProviderPort.PaymentSession(PROVIDER, "sess-" + inv.getArgument(0), "/checkout/x"));
    }

    private CreateDonationIntentWithAccessResult create(PaymentMethod method) {
        return service.createDonationIntentWithAccess(new CreateDonationIntentCommand(newCommandId(),
                campaign.getPublicCode(), "anon:d", 250, "COP", method));
    }

    @Test
    void gateway_storesTheProviderAndTheSession_andOnlyTheHashOfTheStatusToken() {
        CreateDonationIntentWithAccessResult r = create(PaymentMethod.GATEWAY);

        DonationIntent stored = donationIntents.findById(r.intentId()).orElseThrow();
        assertEquals(PROVIDER, stored.getPaymentProvider());
        assertEquals("sess-" + r.intentId(), stored.getPaymentSessionId());
        assertEquals(StatusTokens.hash(r.statusToken()), stored.getAccess().statusTokenHash());
        assertEquals(NOW.plus(Duration.ofHours(24)), stored.getAccess().statusTokenExpiresAt());
        Document raw = mongoTemplate.getCollection(DonationIntentDocument.COLLECTION)
                .find(new Document("_id", r.intentId())).first();
        assertFalse(raw.toJson().contains(r.statusToken()));
        assertEquals("/checkout/x", r.paymentRedirectUrl());
    }

    @Test
    void bankTransfer_hasNoProviderNorSession_butHasAStatusToken() {
        CreateDonationIntentWithAccessResult r = create(PaymentMethod.BANK_TRANSFER);

        DonationIntent stored = donationIntents.findById(r.intentId()).orElseThrow();
        assertNull(stored.getPaymentProvider());
        assertNull(stored.getPaymentSessionId());
        assertNull(r.paymentRedirectUrl());
        assertNotNull(r.statusToken());
        verify(provider, never()).createSession(anyString(), anyLong(), anyString());
    }

    @Test
    void theStatusToken_givesTheStatusForTwentyFourHours_andNothingAfterwards_norWithAnotherToken() {
        CreateDonationIntentWithAccessResult r = create(PaymentMethod.GATEWAY);

        assertEquals("PENDING", reads.findForStatusToken(r.intentId(), r.statusToken()).orElseThrow().status());
        assertTrue(reads.findForStatusToken(r.intentId(), StatusTokens.generate()).isEmpty());
        assertTrue(reads.findForStatusToken(r.intentId(), null).isEmpty());
        clock.set(NOW.plus(Duration.ofHours(24)).minusSeconds(1));
        assertTrue(reads.findForStatusToken(r.intentId(), r.statusToken()).isPresent());
        clock.set(NOW.plus(Duration.ofHours(24)));
        assertTrue(reads.findForStatusToken(r.intentId(), r.statusToken()).isEmpty());
    }

    @Test
    void secondBarrier_aSimulatedSessionIsRejectedWhenThePolicyIsOff_andNothingIsWritten() {
        when(provider.createSession(anyString(), anyLong(), anyString())).thenReturn(
                new PaymentProviderPort.PaymentSession(PaymentProviders.SIMULATED, "sim_1", "/checkout/sim"));
        long claims = processedCommandCount();

        assertThrows(SimulatedPaymentsNotAllowedException.class, () -> create(PaymentMethod.GATEWAY));
        verify(donationIntents, never()).insert(any());
        assertEquals(claims, processedCommandCount());
    }

    @Test
    void aProviderEventOfAnotherProvider_orWithAnotherAmount_isAMismatch() {
        CreateDonationIntentWithAccessResult r = create(PaymentMethod.GATEWAY);
        String session = "sess-" + r.intentId();

        assertThrows(PaymentEventMismatchException.class,
                () -> gatewayPayments.confirmGatewayPayment("OTHER", session, "evt-1", 250, "COP"));
        assertThrows(PaymentEventMismatchException.class,
                () -> gatewayPayments.confirmGatewayPayment(PROVIDER, session, "evt-1", 251, "COP"));
        assertThrows(PaymentEventMismatchException.class,
                () -> gatewayPayments.confirmGatewayPayment(PROVIDER, session, "evt-1", 250, "USD"));
        assertEquals(GatewayPaymentService.Outcome.CONFIRMED,
                gatewayPayments.confirmGatewayPayment(PROVIDER, session, "evt-1", 250, "COP"));
        assertEquals("evt-1", donationIntents.findById(r.intentId()).orElseThrow().getProviderEventId());
    }
}
