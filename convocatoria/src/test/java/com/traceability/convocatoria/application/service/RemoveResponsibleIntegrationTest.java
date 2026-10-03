package com.traceability.convocatoria.application.service;

import com.traceability.convocatoria.application.audit.ConvocatoriaAuditAction;
import com.traceability.convocatoria.application.audit.ConvocatoriaAuditEntry;
import com.traceability.convocatoria.application.command.AssignEmployeeToCampaignCommand;
import com.traceability.convocatoria.application.command.DesignateAdministratorAsCampaignResponsibleCommand;
import com.traceability.convocatoria.application.command.RemoveResponsibleCommand;
import com.traceability.convocatoria.application.command.RemoveResponsibleResult;
import com.traceability.convocatoria.domain.exception.ActorRoleNotAllowedException;
import com.traceability.convocatoria.domain.exception.EmployeeAlreadyAssignedException;
import com.traceability.convocatoria.domain.exception.EmployeeSelfAssignmentNotAllowedException;
import com.traceability.convocatoria.domain.exception.InvalidResponsibleRecipientException;
import com.traceability.convocatoria.domain.exception.LastResponsibleRemovalWithoutReplacementException;
import com.traceability.convocatoria.domain.exception.ReplacementActingRoleRequiredException;
import com.traceability.convocatoria.domain.exception.ResponsibleAssignmentNotFoundException;
import com.traceability.convocatoria.domain.model.ActingRole;
import com.traceability.convocatoria.domain.model.AssignmentStatus;
import com.traceability.convocatoria.domain.model.CampaignAssignment;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.CampaignAssignmentDocument;
import com.traceability.convocatoria.support.AbstractConvocatoriaServiceIntegrationTest;
import com.traceability.convocatoria.support.ConvocatoriaScenarios;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** implementation_plan.md §5 ({@code RemoveResponsible}), §4.4, §7.1, §12.3, §12.4, §13.1, §13.2; ADR-037 §2.5. */
class RemoveResponsibleIntegrationTest extends AbstractConvocatoriaServiceIntegrationTest {

    @Autowired private ConvocatoriaLifecycleService lifecycle;
    @Autowired private ResponsibleAssignmentService service;

    private String campaign;

    @BeforeEach
    void createCampaign() {
        campaign = ConvocatoriaScenarios.createConvocatoria(lifecycle, ADMIN, ORG, flexible());
    }

    private String assignEmployee(String employee) {
        return service.assignEmployee(new AssignEmployeeToCampaignCommand(newCommandId(), ADMIN, campaign, employee))
                .assignmentId();
    }

    private String designate(String administrator) {
        return service.designateAdministrator(new DesignateAdministratorAsCampaignResponsibleCommand(newCommandId(),
                ADMIN, campaign, administrator)).assignmentId();
    }

    private RemoveResponsibleResult remove(String commandId, String responsible, String replacement, ActingRole role) {
        return service.removeResponsible(new RemoveResponsibleCommand(commandId, ADMIN, campaign, responsible,
                replacement, role));
    }

    private long counter() {
        return responsibleState.find(campaign).orElseThrow().activeResponsibleCount();
    }

    private List<ConvocatoriaAuditAction> actions() {
        return audit(campaign).stream().map(ConvocatoriaAuditEntry::action).toList();
    }

    @Test
    void removesWithoutReplacementWhenAnotherResponsibleRemains() {
        String a1 = assignEmployee(EMPLOYEE);
        designate(ADMIN_2);
        assertEquals(2, counter());

        RemoveResponsibleResult result = remove(newCommandId(), EMPLOYEE, null, null);

        assertEquals(a1, result.removedAssignmentId());
        assertNull(result.replacementAssignmentId());
        CampaignAssignment removed = assignments.findById(a1).orElseThrow();
        assertEquals(AssignmentStatus.REMOVED, removed.getStatus());
        assertTrue(removed.getRemovedAt() != null);
        assertEquals(1, counter());
        assertEquals(List.of(ConvocatoriaAuditAction.CONVOCATORIA_CREATED, ConvocatoriaAuditAction.EMPLOYEE_ASSIGNED,
                ConvocatoriaAuditAction.ADMINISTRATOR_DESIGNATED, ConvocatoriaAuditAction.RESPONSIBLE_REMOVED), actions());
    }

    @Test
    void removingLastResponsibleWithoutReplacementIsRejected() {
        String a1 = assignEmployee(EMPLOYEE);
        clearInvocations(auditLog);
        String commandId = newCommandId();

        assertThrows(LastResponsibleRemovalWithoutReplacementException.class, () -> remove(commandId, EMPLOYEE, null, null));

        assertEquals(AssignmentStatus.ACTIVE, assignments.findById(a1).orElseThrow().getStatus());
        assertEquals(1, counter());
        verify(auditLog, never()).append(any());
        assertFalse(processedCommands.find(commandId).isPresent());
    }

    @Test
    void replacementWithEmployeeKeepsCounterAndAppliesEmployeeRules() {
        String a1 = designate(ADMIN_2);
        RemoveResponsibleResult result = remove(newCommandId(), ADMIN_2, EMPLOYEE, ActingRole.EMPLOYEE);

        assertEquals(AssignmentStatus.REMOVED, assignments.findById(a1).orElseThrow().getStatus());
        CampaignAssignment replacement = assignments.findById(result.replacementAssignmentId()).orElseThrow();
        assertEquals(ActingRole.EMPLOYEE, replacement.getActingRole());
        assertEquals(EMPLOYEE, replacement.getEmployeeRef());
        assertEquals(1, counter());
        assertEquals(List.of(ConvocatoriaAuditAction.CONVOCATORIA_CREATED, ConvocatoriaAuditAction.ADMINISTRATOR_DESIGNATED,
                ConvocatoriaAuditAction.RESPONSIBLE_REMOVED, ConvocatoriaAuditAction.EMPLOYEE_ASSIGNED), actions());
    }

    @Test
    void replacementWithSelfDesignatedAdministratorIsMarked() {
        assignEmployee(EMPLOYEE);
        RemoveResponsibleResult result = remove(newCommandId(), EMPLOYEE, ADMIN, ActingRole.ADMINISTRATOR);

        assertTrue(assignments.findById(result.replacementAssignmentId()).orElseThrow().isSelfAssigned());
        ConvocatoriaAuditEntry last = audit(campaign).get(3);
        assertEquals(ConvocatoriaAuditAction.ADMINISTRATOR_DESIGNATED, last.action());
        assertTrue(last.selfAssigned());
        assertEquals(1, counter());
    }

    @Test
    void replacementFollowsRulesOfItsOperationAndFailureRollsBackRemoval() {
        String a1 = assignEmployee(EMPLOYEE);
        String elsewhere = ConvocatoriaScenarios.createConvocatoria(lifecycle, ADMIN, ORG, flexible());
        service.assignEmployee(new AssignEmployeeToCampaignCommand(newCommandId(), ADMIN, elsewhere, EMPLOYEE_2));

        assertThrows(EmployeeAlreadyAssignedException.class,
                () -> remove(newCommandId(), EMPLOYEE, EMPLOYEE_2, ActingRole.EMPLOYEE));
        assertThrows(InvalidResponsibleRecipientException.class,
                () -> remove(newCommandId(), EMPLOYEE, ADMIN_2, ActingRole.EMPLOYEE));
        assertThrows(InvalidResponsibleRecipientException.class,
                () -> remove(newCommandId(), EMPLOYEE, EMPLOYEE_3, ActingRole.ADMINISTRATOR));

        assertEquals(AssignmentStatus.ACTIVE, assignments.findById(a1).orElseThrow().getStatus());
        assertEquals(1, counter());
        assertEquals(2, audit(campaign).size());
    }

    @Test
    void employeeReplacementCannotBeSelfAssigned() {
        designate(ADMIN_2);
        assertThrows(EmployeeSelfAssignmentNotAllowedException.class, () -> service.removeResponsible(
                new RemoveResponsibleCommand(newCommandId(), ADMIN_EMPLOYEE, campaign, ADMIN_2, ADMIN_EMPLOYEE,
                        ActingRole.EMPLOYEE)));
        assertEquals(1, count(CampaignAssignmentDocument.COLLECTION));
    }

    @Test
    void replacementRequiresActingRole() {
        assignEmployee(EMPLOYEE);
        clearInvocations(assignments);
        assertThrows(ReplacementActingRoleRequiredException.class, () -> remove(newCommandId(), EMPLOYEE, EMPLOYEE_2, null));
        verify(assignments, never()).markRemovedIfActive(anyString(), any());
    }

    @Test
    void unknownResponsibleIsRejected() {
        assignEmployee(EMPLOYEE);
        assertThrows(ResponsibleAssignmentNotFoundException.class, () -> remove(newCommandId(), EMPLOYEE_2, null, null));
        assertEquals(1, counter());
    }

    @Test
    void onlyAdministratorCanRemove() {
        assignEmployee(EMPLOYEE);
        designate(ADMIN_2);
        clearInvocations(assignments);
        assertThrows(ActorRoleNotAllowedException.class, () -> service.removeResponsible(
                new RemoveResponsibleCommand(newCommandId(), REPRESENTATIVE, campaign, EMPLOYEE, null, null)));
        assertThrows(ActorRoleNotAllowedException.class, () -> service.removeResponsible(
                new RemoveResponsibleCommand(newCommandId(), EMPLOYEE_2, campaign, EMPLOYEE, null, null)));
        verify(assignments, never()).markRemovedIfActive(anyString(), any());
        assertEquals(2, counter());
    }

    @Test
    void duplicateCommandIdHasSingleEffect() {
        assignEmployee(EMPLOYEE);
        designate(ADMIN_2);
        String commandId = newCommandId();
        RemoveResponsibleResult first = remove(commandId, EMPLOYEE, null, null);
        RemoveResponsibleResult second = remove(commandId, EMPLOYEE, null, null);

        assertEquals(first, second);
        assertEquals(1, counter());
        assertEquals(4, audit(campaign).size());
    }

    @Test
    void forcedFailureRollsBackRemovalCounterAndClaim() {
        String a1 = assignEmployee(EMPLOYEE);
        designate(ADMIN_2);
        doThrow(new IllegalStateException("forced")).when(auditLog).append(any());
        String commandId = newCommandId();

        assertThrows(IllegalStateException.class, () -> remove(commandId, EMPLOYEE, null, null));

        assertEquals(AssignmentStatus.ACTIVE, assignments.findById(a1).orElseThrow().getStatus());
        assertEquals(2, counter());
        assertFalse(processedCommands.find(commandId).isPresent());
    }

    @Test
    void concurrentRemovalsThatWouldLeaveZeroResponsiblesApplyOnlyOne() throws Exception {
        for (int round = 0; round < 3; round++) {
            campaign = ConvocatoriaScenarios.createConvocatoria(lifecycle, ADMIN, ORG, flexible());
            designate(ADMIN);
            designate(ADMIN_2);
            assertEquals(2, counter());
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Callable<RemoveResponsibleResult>> tasks = List.of(
                    () -> { barrier.await(10, TimeUnit.SECONDS); return remove(newCommandId(), ADMIN, null, null); },
                    () -> { barrier.await(10, TimeUnit.SECONDS); return remove(newCommandId(), ADMIN_2, null, null); });
            ExecutorService pool = Executors.newFixedThreadPool(2);
            int applied = 0;
            int rejected = 0;
            try {
                for (Future<RemoveResponsibleResult> f : pool.invokeAll(tasks)) {
                    try {
                        f.get();
                        applied++;
                    } catch (ExecutionException e) {
                        assertInstanceOf(LastResponsibleRemovalWithoutReplacementException.class, e.getCause());
                        rejected++;
                    }
                }
            } finally {
                pool.shutdownNow();
            }
            assertEquals(1, applied);
            assertEquals(1, rejected);
            assertEquals(1, counter());
            long active = assignments.findByCampaignRef(campaign).stream()
                    .filter(a -> a.getStatus() == AssignmentStatus.ACTIVE).count();
            assertEquals(1, active, "never 0 responsibles after a removal");
        }
    }
}
