package com.traceability.convocatoria.scenario;

import com.traceability.convocatoria.application.audit.ConvocatoriaAuditAction;
import com.traceability.convocatoria.application.audit.ConvocatoriaAuditEntry;
import com.traceability.convocatoria.application.command.AssignEmployeeToCampaignCommand;
import com.traceability.convocatoria.application.command.CloseConvocatoriaCommand;
import com.traceability.convocatoria.application.command.ConfirmDonationIntentCommand;
import com.traceability.convocatoria.application.command.CreateConvocatoriaCommand;
import com.traceability.convocatoria.application.command.CreateConvocatoriaResult;
import com.traceability.convocatoria.application.command.CreateDonationIntentCommand;
import com.traceability.convocatoria.application.command.DesignateAdministratorAsCampaignResponsibleCommand;
import com.traceability.convocatoria.application.command.EditConfigurationCommand;
import com.traceability.convocatoria.application.command.RemoveResponsibleCommand;
import com.traceability.convocatoria.application.service.CampaignFundingLedgerService;
import com.traceability.convocatoria.application.service.ConvocatoriaLifecycleService;
import com.traceability.convocatoria.application.service.DonationIntentService;
import com.traceability.convocatoria.application.service.ResponsibleAssignmentService;
import com.traceability.convocatoria.domain.exception.CampaignAlreadyHasDonationsException;
import com.traceability.convocatoria.domain.exception.CampaignClosedException;
import com.traceability.convocatoria.domain.exception.CampaignFundingLimitExceededException;
import com.traceability.convocatoria.domain.exception.CommandIdReusedForDifferentCommandException;
import com.traceability.convocatoria.domain.exception.LastResponsibleRemovalWithoutReplacementException;
import com.traceability.convocatoria.domain.model.ActingRole;
import com.traceability.convocatoria.domain.model.AssignmentStatus;
import com.traceability.convocatoria.domain.model.DonationIntentStatus;
import com.traceability.convocatoria.domain.model.PaymentMethod;
import com.traceability.convocatoria.domain.model.TargetPolicy;
import com.traceability.convocatoria.domain.model.Visibility;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.CampaignAssignmentDocument;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.ConvocatoriaDocument;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.DonationIntentDocument;
import com.traceability.convocatoria.support.AbstractConvocatoriaServiceIntegrationTest;
import com.traceability.convocatoria.support.ConvocatoriaScenarios;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Tarea 9 (implementation_plan.md §12.3, §13.1, §13.2): escenario completo de negocio con audit log exacto
 * (orden, número y marca de autoasignación), reutilización concurrente de {@code commandId} entre tipos (I1) y
 * reversión de las transacciones de §4.4 que no cubren los tests por caso de uso.
 */
class ConvocatoriaBusinessScenarioIntegrationTest extends AbstractConvocatoriaServiceIntegrationTest {

    @Autowired private ConvocatoriaLifecycleService lifecycle;
    @Autowired private ResponsibleAssignmentService responsibles;
    @Autowired private DonationIntentService intents;
    @Autowired private CampaignFundingLedgerService ledgerService;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void fullCampaignLifecycleWithExactAuditLog() {
        clock.set(Instant.parse("2026-10-02T09:00:00Z"));
        CreateConvocatoriaResult created = lifecycle.createConvocatoria(new CreateConvocatoriaCommand(newCommandId(),
                ADMIN, ORG, "Invierno 2026", "Abrigo", Visibility.PUBLIC, ConvocatoriaScenarios.START,
                ConvocatoriaScenarios.END, monetary(TargetPolicy.STRICT, null, 1000L, PaymentMethod.GATEWAY)));
        String campaign = created.campaignRef();

        responsibles.designateAdministrator(new DesignateAdministratorAsCampaignResponsibleCommand(newCommandId(), ADMIN,
                campaign, ADMIN));
        responsibles.assignEmployee(new AssignEmployeeToCampaignCommand(newCommandId(), ADMIN, campaign, EMPLOYEE));
        lifecycle.editConfiguration(new EditConfigurationCommand(newCommandId(), ADMIN, campaign, 1,
                monetary(TargetPolicy.STRICT, null, 1000L, PaymentMethod.GATEWAY, PaymentMethod.BANK_TRANSFER)));

        String firstIntent = intents.createDonationIntent(new CreateDonationIntentCommand(newCommandId(),
                created.publicCode(), "donor-a", 600, "COP", PaymentMethod.BANK_TRANSFER)).intentId();
        String transferIntent = intents.createDonationIntent(new CreateDonationIntentCommand(newCommandId(),
                created.publicCode(), "donor-b", 500, "COP", PaymentMethod.BANK_TRANSFER)).intentId();

        assertThrows(CampaignAlreadyHasDonationsException.class, () -> lifecycle.editConfiguration(
                new EditConfigurationCommand(newCommandId(), ADMIN, campaign, 2,
                        monetary(TargetPolicy.STRICT, null, 1000L, PaymentMethod.GATEWAY))));

        responsibles.removeResponsible(new RemoveResponsibleCommand(newCommandId(), ADMIN, campaign, EMPLOYEE,
                EMPLOYEE_2, ActingRole.EMPLOYEE));
        responsibles.removeResponsible(new RemoveResponsibleCommand(newCommandId(), ADMIN, campaign, ADMIN, null, null));
        assertThrows(LastResponsibleRemovalWithoutReplacementException.class, () -> responsibles.removeResponsible(
                new RemoveResponsibleCommand(newCommandId(), ADMIN, campaign, EMPLOYEE_2, null, null)));

        lifecycle.closeConvocatoria(new CloseConvocatoriaCommand(newCommandId(), ADMIN, campaign));
        assertThrows(CampaignClosedException.class, () -> intents.createDonationIntent(new CreateDonationIntentCommand(
                newCommandId(), created.publicCode(), "donor-c", 10, "COP", PaymentMethod.GATEWAY)));

        // D1: las intenciones previas al cierre siguen confirmables y aplicables; el ledger protege la meta (STRICT).
        // F-1, F-2: confirmar y aplicar son actos separados; el rechazo permanente termina en FUNDING_REJECTED.
        assertTrue(intents.confirmDonationIntent(new ConfirmDonationIntentCommand(firstIntent, ADMIN, "BANK-0")));
        assertTrue(intents.confirmDonationIntent(new ConfirmDonationIntentCommand(transferIntent, ADMIN, "BANK-1")));
        assertTrue(ledgerService.applyFundsForIntent(firstIntent).appliedNow());
        assertThrows(CampaignFundingLimitExceededException.class, () -> ledgerService.applyFundsForIntent(transferIntent));
        assertEquals(600, ledgers.findByCampaignRef(campaign).orElseThrow().clearedAmount());
        assertEquals(DonationIntentStatus.CONFIRMED, donationIntents.findById(transferIntent).orElseThrow().getStatus());
        assertTrue(ledgerService.rejectFundingIfPermanentlyUnfundable(transferIntent));
        assertEquals(DonationIntentStatus.FUNDING_REJECTED,
                donationIntents.findById(transferIntent).orElseThrow().getStatus());

        List<ConvocatoriaAuditEntry> entries = audit(campaign);
        assertEquals(List.of(
                ConvocatoriaAuditAction.CONVOCATORIA_CREATED,
                ConvocatoriaAuditAction.ADMINISTRATOR_DESIGNATED,
                ConvocatoriaAuditAction.EMPLOYEE_ASSIGNED,
                ConvocatoriaAuditAction.CONFIGURATION_EDITED,
                ConvocatoriaAuditAction.DONATION_INTENT_CREATED,
                ConvocatoriaAuditAction.DONATION_INTENT_CREATED,
                ConvocatoriaAuditAction.RESPONSIBLE_REMOVED,
                ConvocatoriaAuditAction.EMPLOYEE_ASSIGNED,
                ConvocatoriaAuditAction.RESPONSIBLE_REMOVED,
                ConvocatoriaAuditAction.CONVOCATORIA_CLOSED), entries.stream().map(ConvocatoriaAuditEntry::action).toList());
        assertEquals(List.of(false, true, false, false, false, false, false, false, false, false),
                entries.stream().map(ConvocatoriaAuditEntry::selfAssigned).toList());
        assertEquals(List.of(ADMIN, ADMIN, ADMIN, ADMIN, "donor-a", "donor-b", ADMIN, ADMIN, ADMIN, ADMIN),
                entries.stream().map(ConvocatoriaAuditEntry::actorRef).toList());

        assertEquals(1, responsibleState.find(campaign).orElseThrow().activeResponsibleCount());
        // D-06 (encargo 5): el cierre pasa la asignación activa (EMPLOYEE_2) a histórica; no queda ninguna activa
        List<String> active = assignments.findByCampaignRef(campaign).stream()
                .filter(a -> a.getStatus() == AssignmentStatus.ACTIVE).map(a -> a.getEmployeeRef()).toList();
        List<String> historical = assignments.findByCampaignRef(campaign).stream()
                .filter(a -> a.getStatus() == AssignmentStatus.HISTORICAL).map(a -> a.getEmployeeRef()).toList();
        assertEquals(List.of(), active);
        assertEquals(List.of(EMPLOYEE_2), historical);
        assertEquals(DonationIntentStatus.CONFIRMED, donationIntents.findById(firstIntent).orElseThrow().getStatus());
    }

    @Test
    void commandIdReusedConcurrentlyByTwoCommandTypesKeepsOnlyTheFirst() throws Exception {
        for (int round = 0; round < 3; round++) {
            String existing = ConvocatoriaScenarios.createConvocatoria(lifecycle, ADMIN, ORG, flexible());
            long convocatoriasBefore = count(ConvocatoriaDocument.COLLECTION);
            String commandId = newCommandId();
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Callable<Object>> tasks = List.of(
                    () -> { barrier.await(10, TimeUnit.SECONDS);
                        return lifecycle.closeConvocatoria(new CloseConvocatoriaCommand(commandId, ADMIN, existing)); },
                    () -> { barrier.await(10, TimeUnit.SECONDS);
                        return lifecycle.createConvocatoria(new CreateConvocatoriaCommand(commandId, ADMIN, ORG, "x",
                                null, Visibility.PUBLIC, ConvocatoriaScenarios.START, ConvocatoriaScenarios.END, flexible())); });
            ExecutorService pool = Executors.newFixedThreadPool(2);
            int applied = 0;
            int rejected = 0;
            try {
                for (Future<Object> f : pool.invokeAll(tasks)) {
                    try {
                        f.get();
                        applied++;
                    } catch (ExecutionException e) {
                        assertInstanceOf(CommandIdReusedForDifferentCommandException.class, e.getCause());
                        rejected++;
                    }
                }
            } finally {
                pool.shutdownNow();
            }
            assertEquals(1, applied);
            assertEquals(1, rejected);
            boolean closed = convocatorias.findByCampaignRef(existing).orElseThrow().getStatus()
                    == com.traceability.convocatoria.domain.model.ConvocatoriaStatus.CLOSED;
            boolean createdNew = count(ConvocatoriaDocument.COLLECTION) == convocatoriasBefore + 1;
            assertTrue(closed ^ createdNew, "exactly one of the two commands took effect");
            assertTrue(processedCommands.find(commandId).isPresent());
        }
    }

    @Test
    void forcedFailureRollsBackDesignation() {
        String campaign = ConvocatoriaScenarios.createConvocatoria(lifecycle, ADMIN, ORG, flexible());
        doThrow(new IllegalStateException("forced")).when(auditLog).append(any());
        String commandId = newCommandId();

        assertThrows(IllegalStateException.class, () -> responsibles.designateAdministrator(
                new DesignateAdministratorAsCampaignResponsibleCommand(commandId, ADMIN, campaign, ADMIN)));

        assertEquals(0, count(CampaignAssignmentDocument.COLLECTION));
        assertFalse(responsibleState.find(campaign).isPresent());
        assertFalse(processedCommands.find(commandId).isPresent());
    }

    @Test
    void forcedFailureRollsBackRemovalWithReplacement() {
        String campaign = ConvocatoriaScenarios.createConvocatoria(lifecycle, ADMIN, ORG, flexible());
        String original = responsibles.assignEmployee(new AssignEmployeeToCampaignCommand(newCommandId(), ADMIN, campaign,
                EMPLOYEE)).assignmentId();
        doThrow(new IllegalStateException("forced")).when(assignments).insert(any());
        String commandId = newCommandId();

        assertThrows(IllegalStateException.class, () -> responsibles.removeResponsible(new RemoveResponsibleCommand(
                commandId, ADMIN, campaign, EMPLOYEE, EMPLOYEE_2, ActingRole.EMPLOYEE)));

        assertEquals(AssignmentStatus.ACTIVE, assignments.findById(original).orElseThrow().getStatus());
        assertEquals(1, count(CampaignAssignmentDocument.COLLECTION));
        assertEquals(1, responsibleState.find(campaign).orElseThrow().activeResponsibleCount());
        assertEquals(2, audit(campaign).size());
        assertFalse(processedCommands.find(commandId).isPresent());
    }

    @Test
    void applicationJoinsAnExternalTransactionAndRollsBackWithIt() {
        String campaign = ConvocatoriaScenarios.createConvocatoria(lifecycle, ADMIN, ORG, flexible());
        String publicCode = convocatorias.findByCampaignRef(campaign).orElseThrow().getPublicCode();
        String intentId = intents.createDonationIntent(new CreateDonationIntentCommand(newCommandId(), publicCode,
                "donor", 100, "COP", PaymentMethod.BANK_TRANSFER)).intentId();
        assertTrue(intents.confirmDonationIntent(new ConfirmDonationIntentCommand(intentId, ADMIN, "BANK-1")));
        Document before = mongoTemplate.findById(intentId, Document.class, DonationIntentDocument.COLLECTION);
        long processedBefore = processedCommandCount();

        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            assertTrue(ledgerService.applyFundsForIntent(intentId).appliedNow());
            throw new IllegalStateException("external transaction fails after the application");
        }));

        assertEquals(before, mongoTemplate.findById(intentId, Document.class, DonationIntentDocument.COLLECTION));
        assertEquals(processedBefore, processedCommandCount());
        assertEquals(0, ledgers.findByCampaignRef(campaign).orElseThrow().clearedAmount());
        assertTrue(ledgerService.applyFundsForIntent(intentId).appliedNow());
        assertEquals(100, ledgers.findByCampaignRef(campaign).orElseThrow().clearedAmount());
    }
}
