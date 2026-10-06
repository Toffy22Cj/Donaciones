package com.traceability.convocatoria.application.service;

import com.traceability.convocatoria.application.audit.ConvocatoriaAuditAction;
import com.traceability.convocatoria.application.audit.ConvocatoriaAuditEntry;
import com.traceability.convocatoria.application.command.CampaignReference;
import com.traceability.convocatoria.application.command.CloseConvocatoriaCommand;
import com.traceability.convocatoria.application.command.CreateDonationIntentCommand;
import com.traceability.convocatoria.application.command.CreateDonationIntentResult;
import com.traceability.convocatoria.application.command.EditConfigurationCommand;
import com.traceability.convocatoria.domain.exception.CampaignClosedException;
import com.traceability.convocatoria.domain.exception.CampaignNotFoundException;
import com.traceability.convocatoria.domain.exception.CashDonationIntentNotSupportedException;
import com.traceability.convocatoria.domain.exception.DonationCurrencyMismatchException;
import com.traceability.convocatoria.domain.exception.DonationTypeNotAcceptedException;
import com.traceability.convocatoria.domain.exception.InvalidDonationAmountException;
import com.traceability.convocatoria.domain.exception.OrganizationNotVerifiedException;
import com.traceability.convocatoria.domain.exception.PaymentMethodNotAcceptedException;
import com.traceability.convocatoria.domain.model.ConfirmationSource;
import com.traceability.convocatoria.domain.model.Convocatoria;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import com.traceability.convocatoria.domain.model.DonationIntent;
import com.traceability.convocatoria.domain.model.DonationIntentStatus;
import com.traceability.convocatoria.domain.model.PaymentMethod;
import com.traceability.convocatoria.domain.model.TargetPolicy;
import com.traceability.convocatoria.domain.model.Visibility;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.ConvocatoriaAuditLogDocument;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.DonationIntentDocument;
import com.traceability.convocatoria.support.AbstractConvocatoriaServiceIntegrationTest;
import com.traceability.convocatoria.support.ConvocatoriaScenarios;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** implementation_plan.md §5 (resolver {@code publicCode}; crear {@code DonationIntent}), §7.3, §9.1, §9.4, §12.4, §13.1. */
class CreateDonationIntentIntegrationTest extends AbstractConvocatoriaServiceIntegrationTest {

    @Autowired private ConvocatoriaLifecycleService lifecycle;
    @Autowired private DonationIntentService service;

    private Convocatoria create(ConvocatoriaConfiguration cfg) {
        String campaignRef = ConvocatoriaScenarios.createConvocatoria(lifecycle, ADMIN, ORG, cfg);
        clearInvocations(donationIntents, auditLog);
        return convocatorias.findByCampaignRef(campaignRef).orElseThrow();
    }

    private CreateDonationIntentResult intent(String commandId, String publicCode, PaymentMethod method, String currency) {
        return service.createDonationIntent(new CreateDonationIntentCommand(commandId, publicCode, "donor-1", 250,
                currency, method));
    }

    private void assertNothingWritten() {
        verify(donationIntents, never()).insert(any());
        verify(auditLog, never()).append(any());
        assertEquals(0, count(DonationIntentDocument.COLLECTION));
    }

    @Test
    void resolvesPublicCodeIncludingPrivateLink() {
        Convocatoria c = create(flexible());
        assertEquals(new CampaignReference(c.getCampaignRef(), ORG), service.resolvePublicCode(c.getPublicCode()));
        String privateRef = lifecycle.createConvocatoria(new com.traceability.convocatoria.application.command
                .CreateConvocatoriaCommand(newCommandId(), ADMIN, ORG, "p", null, Visibility.PRIVATE_LINK,
                ConvocatoriaScenarios.START, ConvocatoriaScenarios.END, flexible())).campaignRef();
        Convocatoria p = convocatorias.findByCampaignRef(privateRef).orElseThrow();
        assertEquals(privateRef, service.resolvePublicCode(p.getPublicCode()).campaignRef());
        assertThrows(CampaignNotFoundException.class, () -> service.resolvePublicCode("NOPE"));
    }

    @Test
    void createsGatewayIntentWithStableIdsAndAudit() {
        Convocatoria c = create(flexible());
        String commandId = newCommandId();
        CreateDonationIntentResult result = intent(commandId, c.getPublicCode(), PaymentMethod.GATEWAY, "COP");

        DonationIntent stored = donationIntents.findById(result.intentId()).orElseThrow();
        assertEquals(result.fundId(), stored.getFundId());
        assertNotEquals(stored.getIntentId(), stored.getFundId());
        assertEquals(DonationIntentStatus.PENDING, stored.getStatus());
        assertEquals(ConfirmationSource.PAYMENT_PROVIDER, stored.getConfirmationSource());
        assertEquals(PaymentMethod.GATEWAY, stored.getPaymentMethod());
        assertEquals(c.getCampaignRef(), stored.getCampaignRef());
        assertEquals(ORG, stored.getOrganizationRef());
        assertEquals("donor-1", stored.getDonorRef());
        assertEquals(250, stored.getAmount());
        assertEquals(1L, stored.getConfigurationVersion());
        assertNull(stored.getExpiresAt());
        assertNull(stored.getPaymentSessionId());

        List<ConvocatoriaAuditEntry> entries = audit(c.getCampaignRef());
        assertEquals(ConvocatoriaAuditAction.DONATION_INTENT_CREATED, entries.get(entries.size() - 1).action());
        assertEquals(result.intentId(), entries.get(entries.size() - 1).targetRef());
    }

    @Test
    void bankTransferIntentExpiresAfterConfiguredDuration() {
        Convocatoria c = create(flexible());
        Instant now = Instant.parse("2026-10-05T12:00:00Z");
        clock.set(now);
        CreateDonationIntentResult result = intent(newCommandId(), c.getPublicCode(), PaymentMethod.BANK_TRANSFER, "COP");

        DonationIntent stored = donationIntents.findById(result.intentId()).orElseThrow();
        assertEquals(ConfirmationSource.ORGANIZATION, stored.getConfirmationSource());
        assertEquals(now.plus(Duration.ofHours(72)), stored.getExpiresAt());
    }

    @Test
    void recordsConfigurationVersionReadAtCreation() {
        Convocatoria c = create(flexible());
        lifecycle.editConfiguration(new EditConfigurationCommand(newCommandId(), ADMIN, c.getCampaignRef(), 1,
                monetary(TargetPolicy.FLEXIBLE, null, 1000L, PaymentMethod.GATEWAY)));
        CreateDonationIntentResult result = intent(newCommandId(), c.getPublicCode(), PaymentMethod.GATEWAY, "COP");
        assertEquals(2L, donationIntents.findById(result.intentId()).orElseThrow().getConfigurationVersion());
    }

    @Test
    void closedConvocatoriaRejectsNewIntent() {
        Convocatoria c = create(flexible());
        lifecycle.closeConvocatoria(new CloseConvocatoriaCommand(newCommandId(), ADMIN, c.getCampaignRef()));
        clearInvocations(auditLog);
        assertThrows(CampaignClosedException.class, () -> intent(newCommandId(), c.getPublicCode(), PaymentMethod.GATEWAY, "COP"));
        assertNothingWritten();
    }

    @Test
    void unverifiedOrganizationIsRejected() {
        Convocatoria c = create(flexible());
        organizationVerification.markUnverified(ORG);
        assertThrows(OrganizationNotVerifiedException.class,
                () -> intent(newCommandId(), c.getPublicCode(), PaymentMethod.GATEWAY, "COP"));
        assertNothingWritten();
    }

    @Test
    void inKindOnlyConvocatoriaRejectsMonetaryIntent() {
        Convocatoria c = create(inKindOnly());
        assertThrows(DonationTypeNotAcceptedException.class,
                () -> intent(newCommandId(), c.getPublicCode(), PaymentMethod.GATEWAY, "COP"));
        assertNothingWritten();
    }

    @Test
    void paymentMethodNotAcceptedIsRejected() {
        Convocatoria c = create(monetary(TargetPolicy.FLEXIBLE, null, 1000L, PaymentMethod.GATEWAY));
        assertThrows(PaymentMethodNotAcceptedException.class,
                () -> intent(newCommandId(), c.getPublicCode(), PaymentMethod.BANK_TRANSFER, "COP"));
        assertNothingWritten();
    }

    @Test
    void cashIsRejectedInThisCut() {
        Convocatoria c = create(flexible());
        assertThrows(CashDonationIntentNotSupportedException.class,
                () -> intent(newCommandId(), c.getPublicCode(), PaymentMethod.CASH, "COP"));
        assertNothingWritten();
    }

    @Test
    void currencyMustMatchConvocatoria() {
        Convocatoria c = create(flexible());
        assertThrows(DonationCurrencyMismatchException.class,
                () -> intent(newCommandId(), c.getPublicCode(), PaymentMethod.GATEWAY, "USD"));
        assertNothingWritten();
    }

    @Test
    void amountMustBePositive() {
        Convocatoria c = create(flexible());
        assertThrows(InvalidDonationAmountException.class, () -> service.createDonationIntent(
                new CreateDonationIntentCommand(newCommandId(), c.getPublicCode(), "d", 0, "COP", PaymentMethod.GATEWAY)));
        assertNothingWritten();
    }

    @Test
    void unknownPublicCodeIsRejectedWithoutClaim() {
        String commandId = newCommandId();
        assertThrows(CampaignNotFoundException.class, () -> intent(commandId, "NOPE", PaymentMethod.GATEWAY, "COP"));
        assertFalse(processedCommands.find(commandId).isPresent());
    }

    @Test
    void datesDoNotLimitCreationInThisCut() {
        Convocatoria c = create(flexible());
        clock.set(ConvocatoriaScenarios.END.plus(Duration.ofDays(30)));
        intent(newCommandId(), c.getPublicCode(), PaymentMethod.GATEWAY, "COP");
        assertEquals(1, count(DonationIntentDocument.COLLECTION));
    }

    @Test
    void duplicateCommandIdReturnsOriginalIntentAndFundIds() {
        Convocatoria c = create(flexible());
        String commandId = newCommandId();
        CreateDonationIntentResult first = intent(commandId, c.getPublicCode(), PaymentMethod.GATEWAY, "COP");
        CreateDonationIntentResult second = intent(commandId, c.getPublicCode(), PaymentMethod.GATEWAY, "COP");

        assertEquals(first, second);
        assertEquals(1, count(DonationIntentDocument.COLLECTION));
        assertEquals(2, count(ConvocatoriaAuditLogDocument.COLLECTION));
    }

    @Test
    void sameDonorCanLegitimatelyDonateTwiceWithDifferentCommandIds() {
        Convocatoria c = create(flexible());
        intent(newCommandId(), c.getPublicCode(), PaymentMethod.GATEWAY, "COP");
        intent(newCommandId(), c.getPublicCode(), PaymentMethod.GATEWAY, "COP");
        assertEquals(2, count(DonationIntentDocument.COLLECTION));
    }

    @Test
    void forcedFailureRollsBackIntentAuditAndClaimSoResendExecutesAsNew() {
        Convocatoria c = create(flexible());
        doThrow(new IllegalStateException("forced")).when(auditLog).append(any());
        String commandId = newCommandId();

        assertThrows(IllegalStateException.class, () -> intent(commandId, c.getPublicCode(), PaymentMethod.GATEWAY, "COP"));
        assertEquals(0, count(DonationIntentDocument.COLLECTION));
        assertFalse(processedCommands.find(commandId).isPresent());

        org.mockito.Mockito.reset(auditLog);
        CreateDonationIntentResult result = intent(commandId, c.getPublicCode(), PaymentMethod.GATEWAY, "COP");
        assertEquals(1, count(DonationIntentDocument.COLLECTION));
        assertEquals(result.intentId(), donationIntents.findById(result.intentId()).orElseThrow().getIntentId());
    }

    @Test
    void concurrentSameCommandIdCreatesOneIntentAndBothReceiveOriginalResult() throws Exception {
        Convocatoria c = create(flexible());
        for (int round = 0; round < 3; round++) {
            String commandId = newCommandId();
            CyclicBarrier barrier = new CyclicBarrier(2);
            Callable<CreateDonationIntentResult> task = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return intent(commandId, c.getPublicCode(), PaymentMethod.GATEWAY, "COP");
            };
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<CreateDonationIntentResult>> futures = pool.invokeAll(List.of(task, task));
                assertEquals(futures.get(0).get(), futures.get(1).get());
            } finally {
                pool.shutdownNow();
            }
        }
        assertEquals(3, count(DonationIntentDocument.COLLECTION));
    }
}
