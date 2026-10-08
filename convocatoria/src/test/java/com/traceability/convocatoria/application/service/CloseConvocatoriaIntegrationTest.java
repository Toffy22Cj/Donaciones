package com.traceability.convocatoria.application.service;

import com.traceability.convocatoria.application.audit.ConvocatoriaAuditAction;
import com.traceability.convocatoria.application.command.CloseConvocatoriaCommand;
import com.traceability.convocatoria.application.command.CloseConvocatoriaResult;
import com.traceability.convocatoria.application.command.AssignEmployeeToCampaignCommand;
import com.traceability.convocatoria.application.command.CreateConvocatoriaCommand;
import com.traceability.convocatoria.application.command.DesignateAdministratorAsCampaignResponsibleCommand;
import com.traceability.convocatoria.application.command.RemoveResponsibleCommand;
import com.traceability.convocatoria.domain.model.AssignmentStatus;
import com.traceability.convocatoria.domain.model.CampaignAssignment;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
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
    @Autowired private ResponsibleAssignmentService responsibles;

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

    private Map<String, AssignmentStatus> statusByResponsible(String campaignRef) {
        return assignments.findByCampaignRef(campaignRef).stream()
                .collect(Collectors.toMap(CampaignAssignment::getEmployeeRef, CampaignAssignment::getStatus, (a, b) -> b));
    }

    /** D-06 (Carlos, 2026-10-08): cerrar pasa las asignaciones activas a historial; las retiradas no cambian. */
    @Test
    void closing_movesTheActiveAssignmentsToHistory_andFreesTheEmployeeForAnotherCampaign() {
        String campaignRef = create();
        responsibles.assignEmployee(new AssignEmployeeToCampaignCommand(newCommandId(), ADMIN, campaignRef, EMPLOYEE));
        responsibles.designateAdministrator(new DesignateAdministratorAsCampaignResponsibleCommand(newCommandId(), ADMIN,
                campaignRef, ADMIN_2));
        responsibles.assignEmployee(new AssignEmployeeToCampaignCommand(newCommandId(), ADMIN, campaignRef, EMPLOYEE_2));
        responsibles.removeResponsible(new RemoveResponsibleCommand(newCommandId(), ADMIN, campaignRef, EMPLOYEE_2, null, null));
        Instant closedAt = clock.instant();

        service.closeConvocatoria(new CloseConvocatoriaCommand(newCommandId(), ADMIN, campaignRef));

        assertEquals(Map.of(EMPLOYEE, AssignmentStatus.HISTORICAL, ADMIN_2, AssignmentStatus.HISTORICAL,
                EMPLOYEE_2, AssignmentStatus.REMOVED), statusByResponsible(campaignRef));
        assignments.findByCampaignRef(campaignRef).stream()
                .filter(a -> a.getStatus() == AssignmentStatus.HISTORICAL)
                .forEach(a -> assertEquals(closedAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                        a.getRemovedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)));
        assertEquals("2", audit(campaignRef).get(audit(campaignRef).size() - 1).details().get("historicalAssignments"));
        // el índice único del EMPLOYEE activo ya no lo bloquea
        String next = create();
        responsibles.assignEmployee(new AssignEmployeeToCampaignCommand(newCommandId(), ADMIN, next, EMPLOYEE));
        assertEquals(AssignmentStatus.ACTIVE, statusByResponsible(next).get(EMPLOYEE));
    }

    /** D-06: en la misma transacción que el cierre; si el cierre falla, ninguna asignación cambia. */
    @Test
    void ifTheCloseFails_theAssignmentsStayActive_andTheCampaignStaysOpen() {
        String campaignRef = create();
        responsibles.assignEmployee(new AssignEmployeeToCampaignCommand(newCommandId(), ADMIN, campaignRef, EMPLOYEE));
        doThrow(new IllegalStateException("fallo simulado del registro de auditoría")).when(auditLog)
                .append(org.mockito.ArgumentMatchers.argThat(e -> e != null
                        && e.action() == ConvocatoriaAuditAction.CONVOCATORIA_CLOSED));
        String commandId = newCommandId();

        assertThrows(IllegalStateException.class,
                () -> service.closeConvocatoria(new CloseConvocatoriaCommand(commandId, ADMIN, campaignRef)));

        verify(assignments).markHistoricalByCampaignRef(org.mockito.ArgumentMatchers.eq(campaignRef), any());
        assertEquals(Map.of(EMPLOYEE, AssignmentStatus.ACTIVE), statusByResponsible(campaignRef));
        assertEquals(ConvocatoriaStatus.OPEN, convocatorias.findByCampaignRef(campaignRef).orElseThrow().getStatus());
        assertFalse(processedCommands.find(commandId).isPresent());
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
