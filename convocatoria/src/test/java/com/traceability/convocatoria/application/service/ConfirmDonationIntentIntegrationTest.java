package com.traceability.convocatoria.application.service;

import com.traceability.convocatoria.application.command.ConfirmDonationIntentCommand;
import com.traceability.convocatoria.application.command.CreateDonationIntentCommand;
import com.traceability.convocatoria.domain.exception.ActorNotInCampaignOrganizationException;
import com.traceability.convocatoria.domain.exception.ActorRoleNotAllowedException;
import com.traceability.convocatoria.domain.exception.DonationIntentExpiredException;
import com.traceability.convocatoria.domain.exception.DonationIntentNotFoundException;
import com.traceability.convocatoria.domain.exception.GatewayIntentManualConfirmationNotAllowedException;
import com.traceability.convocatoria.domain.exception.IncompleteConfirmationException;
import com.traceability.convocatoria.domain.model.Convocatoria;
import com.traceability.convocatoria.domain.model.DonationIntent;
import com.traceability.convocatoria.domain.model.DonationIntentStatus;
import com.traceability.convocatoria.domain.model.PaymentMethod;
import com.traceability.convocatoria.domain.model.TargetPolicy;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.CampaignFundingLedgerDocument;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.DonationIntentDocument;
import com.traceability.convocatoria.support.AbstractConvocatoriaServiceIntegrationTest;
import com.traceability.convocatoria.support.ConvocatoriaScenarios;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * implementation_plan.md §7.2, §9.2, §9.4, §13.1; ADR-037 Enmienda 2 §3.1: confirmación manual solo por
 * {@code ADMINISTRATOR} de la organización, nunca para intenciones de pasarela; transición aplicada una sola vez ante
 * confirmaciones secuenciales y concurrentes, con aserción negativa de que la segunda no modifica nada (regla 2.5);
 * la confirmación no toca el ledger ni el registro de comandos (F-1, F-2).
 */
class ConfirmDonationIntentIntegrationTest extends AbstractConvocatoriaServiceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    @Autowired private ConvocatoriaLifecycleService lifecycle;
    @Autowired private DonationIntentService service;
    @Autowired private CampaignFundingLedgerService ledgerService;

    private Convocatoria convocatoria;

    @BeforeEach
    void createCampaign() {
        String campaignRef = ConvocatoriaScenarios.createConvocatoria(lifecycle, ADMIN, ORG, flexible());
        convocatoria = convocatorias.findByCampaignRef(campaignRef).orElseThrow();
        clock.set(NOW);
    }

    private String createIntent(PaymentMethod method) {
        return service.createDonationIntent(new CreateDonationIntentCommand(newCommandId(), convocatoria.getPublicCode(),
                "donor-1", 100, "COP", method)).intentId();
    }

    private Document raw(String intentId) {
        return mongoTemplate.findById(intentId, Document.class, DonationIntentDocument.COLLECTION);
    }

    private void assertUntouched(String intentId, Document before) {
        assertEquals(before, raw(intentId));
        verify(donationIntents, never()).confirmIfPending(anyString(), any());
    }

    @Test
    void administratorConfirmationRecordsWhoWhenMethodAndReference() {
        String intentId = createIntent(PaymentMethod.BANK_TRANSFER);
        clock.set(NOW.plus(Duration.ofHours(1)));

        assertTrue(service.confirmDonationIntent(new ConfirmDonationIntentCommand(intentId, ADMIN, "BANK-REF-1")));

        DonationIntent stored = donationIntents.findById(intentId).orElseThrow();
        assertEquals(DonationIntentStatus.CONFIRMED, stored.getStatus());
        assertEquals(new DonationIntent.Confirmation(ADMIN, NOW.plus(Duration.ofHours(1)), PaymentMethod.BANK_TRANSFER,
                "BANK-REF-1"), stored.getConfirmation());
    }

    @Test
    void employeeAndRepresentativeCannotConfirm() {
        String intentId = createIntent(PaymentMethod.BANK_TRANSFER);
        Document before = raw(intentId);
        assertThrows(ActorRoleNotAllowedException.class,
                () -> service.confirmDonationIntent(new ConfirmDonationIntentCommand(intentId, EMPLOYEE, "R")));
        assertThrows(ActorRoleNotAllowedException.class,
                () -> service.confirmDonationIntent(new ConfirmDonationIntentCommand(intentId, REPRESENTATIVE, "R")));
        assertUntouched(intentId, before);
    }

    @Test
    void administratorOfAnotherOrganizationCannotConfirm() {
        String intentId = createIntent(PaymentMethod.BANK_TRANSFER);
        Document before = raw(intentId);
        assertThrows(ActorNotInCampaignOrganizationException.class,
                () -> service.confirmDonationIntent(new ConfirmDonationIntentCommand(intentId, OTHER_ORG_ADMIN, "R")));
        assertUntouched(intentId, before);
    }

    @Test
    void gatewayIntentCannotBeConfirmedManuallyEvenByAnAdministrator() {
        String intentId = createIntent(PaymentMethod.GATEWAY);
        Document before = raw(intentId);
        assertThrows(GatewayIntentManualConfirmationNotAllowedException.class,
                () -> service.confirmDonationIntent(new ConfirmDonationIntentCommand(intentId, ADMIN, "R")));
        assertUntouched(intentId, before);
        assertEquals(DonationIntentStatus.PENDING, donationIntents.findById(intentId).orElseThrow().getStatus());
    }

    @Test
    void secondSequentialConfirmationIsANoOpAndModifiesNothing() {
        String intentId = createIntent(PaymentMethod.BANK_TRANSFER);
        assertTrue(service.confirmDonationIntent(new ConfirmDonationIntentCommand(intentId, ADMIN, "REF-1")));
        Document before = raw(intentId);

        clock.set(NOW.plusSeconds(30));
        assertFalse(service.confirmDonationIntent(new ConfirmDonationIntentCommand(intentId, ADMIN_2, "REF-2")));

        assertEquals(before, raw(intentId));
    }

    @Test
    void expiredBankTransferCannotBeConfirmed() {
        String intentId = createIntent(PaymentMethod.BANK_TRANSFER);
        Document before = raw(intentId);
        clock.set(NOW.plus(Duration.ofHours(72)));

        assertThrows(DonationIntentExpiredException.class,
                () -> service.confirmDonationIntent(new ConfirmDonationIntentCommand(intentId, ADMIN, "LATE")));

        assertEquals(before, raw(intentId));
        assertNull(donationIntents.findById(intentId).orElseThrow().getConfirmation());
    }

    @Test
    void incompleteConfirmationIsRejected() {
        String intentId = createIntent(PaymentMethod.BANK_TRANSFER);
        assertThrows(IncompleteConfirmationException.class,
                () -> service.confirmDonationIntent(new ConfirmDonationIntentCommand(intentId, ADMIN, " ")));
        assertEquals(DonationIntentStatus.PENDING, donationIntents.findById(intentId).orElseThrow().getStatus());
    }

    @Test
    void unknownIntentIsRejected() {
        assertThrows(DonationIntentNotFoundException.class,
                () -> service.confirmDonationIntent(new ConfirmDonationIntentCommand("missing", ADMIN, "R")));
    }

    /**
     * F-1, F-2: la confirmación no exige fondos aplicados ni capacidad, y no toca el ledger ni el registro de comandos
     * (no hay reclamo {@code APPLY_FUNDS}). Convocatoria {@code STRICT} con la meta ya alcanzada.
     */
    @Test
    void confirmationNeitherRequiresNorAppliesFunds() {
        String strict = ConvocatoriaScenarios.createConvocatoria(lifecycle, ADMIN, ORG,
                monetary(TargetPolicy.STRICT, null, 1000L, PaymentMethod.BANK_TRANSFER));
        String publicCode = convocatorias.findByCampaignRef(strict).orElseThrow().getPublicCode();
        String full = service.createDonationIntent(new CreateDonationIntentCommand(newCommandId(), publicCode, "donor-1",
                1000, "COP", PaymentMethod.BANK_TRANSFER)).intentId();
        assertTrue(service.confirmDonationIntent(new ConfirmDonationIntentCommand(full, ADMIN, "REF-FULL")));
        assertTrue(ledgerService.applyFundsForIntent(full).appliedNow());
        String intentId = service.createDonationIntent(new CreateDonationIntentCommand(newCommandId(), publicCode,
                "donor-2", 300, "COP", PaymentMethod.BANK_TRANSFER)).intentId();
        Document ledgerBefore = mongoTemplate.findById(strict, Document.class, CampaignFundingLedgerDocument.COLLECTION);
        long processedBefore = processedCommandCount();

        assertTrue(service.confirmDonationIntent(new ConfirmDonationIntentCommand(intentId, ADMIN, "REF-1")));

        assertEquals(DonationIntentStatus.CONFIRMED, donationIntents.findById(intentId).orElseThrow().getStatus());
        assertEquals(ledgerBefore, mongoTemplate.findById(strict, Document.class, CampaignFundingLedgerDocument.COLLECTION));
        assertEquals(processedBefore, processedCommandCount());
        verify(ledgers, never()).incrementUnconditionally(anyString(), anyLong());
        verify(ledgers, org.mockito.Mockito.times(1)).incrementWithinTarget(anyString(), anyLong(), anyLong());
    }

    @Test
    void concurrentConfirmationsApplyTheTransitionOnce() throws Exception {
        for (int round = 0; round < 5; round++) {
            String intentId = createIntent(PaymentMethod.BANK_TRANSFER);
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Callable<Boolean>> tasks = List.of(
                    () -> { barrier.await(10, TimeUnit.SECONDS);
                        return service.confirmDonationIntent(new ConfirmDonationIntentCommand(intentId, ADMIN, "REF-A")); },
                    () -> { barrier.await(10, TimeUnit.SECONDS);
                        return service.confirmDonationIntent(new ConfirmDonationIntentCommand(intentId, ADMIN_2, "REF-B")); });
            ExecutorService pool = Executors.newFixedThreadPool(2);
            int applied = 0;
            try {
                for (Future<Boolean> f : pool.invokeAll(tasks)) {
                    if (f.get()) {
                        applied++;
                    }
                }
            } finally {
                pool.shutdownNow();
            }
            assertEquals(1, applied);
            DonationIntent stored = donationIntents.findById(intentId).orElseThrow();
            assertEquals(DonationIntentStatus.CONFIRMED, stored.getStatus());
            String winnerRef = stored.getConfirmation().reference();
            String winner = stored.getConfirmation().confirmedBy();
            assertTrue(("REF-A".equals(winnerRef) && ADMIN.equals(winner)) || ("REF-B".equals(winnerRef) && ADMIN_2.equals(winner)),
                    "the stored confirmation belongs entirely to the single winner");
        }
    }
}
