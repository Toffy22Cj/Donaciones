package com.traceability.convocatoria.application.service;

import com.traceability.convocatoria.application.audit.ConvocatoriaAuditAction;
import com.traceability.convocatoria.application.command.CloseConvocatoriaCommand;
import com.traceability.convocatoria.application.command.CloseConvocatoriaResult;
import com.traceability.convocatoria.application.command.CreateConvocatoriaCommand;
import com.traceability.convocatoria.domain.exception.ActorNotInCampaignOrganizationException;
import com.traceability.convocatoria.domain.exception.ActorRoleNotAllowedException;
import com.traceability.convocatoria.domain.exception.CampaignAlreadyClosedException;
import com.traceability.convocatoria.domain.exception.CampaignNotFoundException;
import com.traceability.convocatoria.domain.model.ConvocatoriaStatus;
import com.traceability.convocatoria.domain.model.Visibility;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.ConvocatoriaAuditLogDocument;
import com.traceability.convocatoria.support.AbstractConvocatoriaServiceIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * implementation_plan.md §10 (cierre manual); Enmienda §3.4; D1 (ADR-037 §2.6bis).
 */
class CloseConvocatoriaIntegrationTest extends AbstractConvocatoriaServiceIntegrationTest {

    @Autowired private ConvocatoriaLifecycleService service;
    @Autowired private DonationIntentService intents;

    private String create() {
        return service.createConvocatoria(new CreateConvocatoriaCommand(newCommandId(), ADMIN, ORG, "t", null,
                Visibility.PUBLIC, CreateConvocatoriaIntegrationTest.START, CreateConvocatoriaIntegrationTest.END,
                flexible())).campaignRef();
    }

    @Test
    void closesOpenConvocatoriaAndAudits() {
        String campaignRef = create();
        CloseConvocatoriaResult result = service.closeConvocatoria(new CloseConvocatoriaCommand(newCommandId(), ADMIN, campaignRef));

        assertEquals(campaignRef, result.campaignRef());
        assertEquals(ConvocatoriaStatus.CLOSED, convocatorias.findByCampaignRef(campaignRef).orElseThrow().getStatus());
        assertEquals(List.of(ConvocatoriaAuditAction.CONVOCATORIA_CREATED, ConvocatoriaAuditAction.CONVOCATORIA_CLOSED),
                audit(campaignRef).stream().map(e -> e.action()).toList());
    }

    @Test
    void duplicateCommandIdReturnsOriginalResult() {
        String campaignRef = create();
        String commandId = newCommandId();
        CloseConvocatoriaResult first = service.closeConvocatoria(new CloseConvocatoriaCommand(commandId, ADMIN, campaignRef));
        CloseConvocatoriaResult second = service.closeConvocatoria(new CloseConvocatoriaCommand(commandId, ADMIN, campaignRef));

        assertEquals(first, second);
        assertEquals(2, count(ConvocatoriaAuditLogDocument.COLLECTION));
    }

    @Test
    void secondCloseWithAnotherCommandIdIsRejected() {
        String campaignRef = create();
        service.closeConvocatoria(new CloseConvocatoriaCommand(newCommandId(), ADMIN, campaignRef));
        clearInvocations(convocatorias, auditLog);
        String commandId = newCommandId();

        assertThrows(CampaignAlreadyClosedException.class,
                () -> service.closeConvocatoria(new CloseConvocatoriaCommand(commandId, ADMIN, campaignRef)));

        verify(convocatorias, never()).closeIfOpen(anyString());
        verify(auditLog, never()).append(any());
        assertFalse(processedCommands.find(commandId).isPresent());
        assertEquals(2, count(ConvocatoriaAuditLogDocument.COLLECTION));
    }

    @Test
    void onlyAdministratorOfTheOrganizationCanClose() {
        String campaignRef = create();
        clearInvocations(convocatorias);
        assertThrows(ActorRoleNotAllowedException.class,
                () -> service.closeConvocatoria(new CloseConvocatoriaCommand(newCommandId(), EMPLOYEE, campaignRef)));
        assertThrows(ActorRoleNotAllowedException.class,
                () -> service.closeConvocatoria(new CloseConvocatoriaCommand(newCommandId(), REPRESENTATIVE, campaignRef)));
        assertThrows(ActorNotInCampaignOrganizationException.class,
                () -> service.closeConvocatoria(new CloseConvocatoriaCommand(newCommandId(), OTHER_ORG_ADMIN, campaignRef)));
        verify(convocatorias, never()).closeIfOpen(anyString());
        assertEquals(ConvocatoriaStatus.OPEN, convocatorias.findByCampaignRef(campaignRef).orElseThrow().getStatus());
    }

    @Test
    void unknownCampaignIsRejected() {
        assertThrows(CampaignNotFoundException.class,
                () -> service.closeConvocatoria(new CloseConvocatoriaCommand(newCommandId(), ADMIN, "missing")));
    }

    @Test
    void forcedFailureRollsBackCloseAndClaim() {
        String campaignRef = create();
        doThrow(new IllegalStateException("forced audit failure")).when(auditLog).append(any());
        String commandId = newCommandId();

        assertThrows(IllegalStateException.class,
                () -> service.closeConvocatoria(new CloseConvocatoriaCommand(commandId, ADMIN, campaignRef)));

        assertEquals(ConvocatoriaStatus.OPEN, convocatorias.findByCampaignRef(campaignRef).orElseThrow().getStatus());
        assertFalse(processedCommands.find(commandId).isPresent());
    }

    @Test
    void newIntentRejectedAfterCloseButExistingIntentStaysPendingAndConfirmable() {
        String campaignRef = create();
        String publicCode = convocatorias.findByCampaignRef(campaignRef).orElseThrow().getPublicCode();
        String intentId = intents.createDonationIntent(new com.traceability.convocatoria.application.command
                .CreateDonationIntentCommand(newCommandId(), publicCode, "donor", 100, "COP",
                com.traceability.convocatoria.domain.model.PaymentMethod.BANK_TRANSFER)).intentId();

        service.closeConvocatoria(new CloseConvocatoriaCommand(newCommandId(), ADMIN, campaignRef));

        assertThrows(com.traceability.convocatoria.domain.exception.CampaignClosedException.class,
                () -> intents.createDonationIntent(new com.traceability.convocatoria.application.command
                        .CreateDonationIntentCommand(newCommandId(), publicCode, "donor", 100, "COP",
                        com.traceability.convocatoria.domain.model.PaymentMethod.GATEWAY)));
        assertEquals(com.traceability.convocatoria.domain.model.DonationIntentStatus.PENDING,
                donationIntents.findById(intentId).orElseThrow().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(intents.confirmDonationIntent(
                new com.traceability.convocatoria.application.command.ConfirmDonationIntentCommand(intentId, ADMIN, "BANK-1")));
        assertEquals(com.traceability.convocatoria.domain.model.DonationIntentStatus.CONFIRMED,
                donationIntents.findById(intentId).orElseThrow().getStatus());
    }
}
