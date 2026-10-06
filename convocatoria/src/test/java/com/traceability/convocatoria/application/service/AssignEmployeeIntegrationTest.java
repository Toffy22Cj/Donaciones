package com.traceability.convocatoria.application.service;

import com.traceability.convocatoria.application.audit.ConvocatoriaAuditAction;
import com.traceability.convocatoria.application.audit.ConvocatoriaAuditEntry;
import com.traceability.convocatoria.application.command.AssignEmployeeToCampaignCommand;
import com.traceability.convocatoria.application.command.AssignmentResult;
import com.traceability.convocatoria.domain.exception.ActorNotInCampaignOrganizationException;
import com.traceability.convocatoria.domain.exception.ActorRoleNotAllowedException;
import com.traceability.convocatoria.domain.exception.EmployeeAlreadyAssignedException;
import com.traceability.convocatoria.domain.exception.EmployeeSelfAssignmentNotAllowedException;
import com.traceability.convocatoria.domain.exception.InvalidResponsibleRecipientException;
import com.traceability.convocatoria.domain.exception.ResponsibleAlreadyActiveInCampaignException;
import com.traceability.convocatoria.domain.model.ActingRole;
import com.traceability.convocatoria.domain.model.AssignmentStatus;
import com.traceability.convocatoria.domain.model.CampaignAssignment;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.CampaignAssignmentDocument;
import com.traceability.convocatoria.support.AbstractConvocatoriaServiceIntegrationTest;
import com.traceability.convocatoria.support.ConvocatoriaScenarios;
import com.traceability.convocatoria.support.FakeIdentityPrincipalPort;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** implementation_plan.md §5 ({@code AssignEmployeeToCampaign}), §6, §7.1, §12.3, §12.4, §13.1, §13.2. */
class AssignEmployeeIntegrationTest extends AbstractConvocatoriaServiceIntegrationTest {

    @Autowired private ConvocatoriaLifecycleService lifecycle;
    @Autowired private ResponsibleAssignmentService service;

    private String campaign;

    @BeforeEach
    void createCampaign() {
        campaign = ConvocatoriaScenarios.createConvocatoria(lifecycle, ADMIN, ORG, flexible());
        clearInvocations(assignments, responsibleState, auditLog);
    }

    private AssignmentResult assign(String commandId, String actor, String campaignRef, String employee) {
        return service.assignEmployee(new AssignEmployeeToCampaignCommand(commandId, actor, campaignRef, employee));
    }

    private long counter(String campaignRef) {
        return responsibleState.find(campaignRef).map(s -> s.activeResponsibleCount()).orElse(0L);
    }

    private void assertNoWrites() {
        verify(assignments, never()).insert(any());
        verify(responsibleState, never()).increment(anyString());
        verify(auditLog, never()).append(any());
    }

    @Test
    void assignsEmployeeInitialisesCounterAndAudits() {
        assertEquals(0, counter(campaign), "R2: a new campaign has 0 responsibles until its first assignment");
        String commandId = newCommandId();
        AssignmentResult result = assign(commandId, ADMIN, campaign, EMPLOYEE);

        CampaignAssignment stored = assignments.findById(result.assignmentId()).orElseThrow();
        assertEquals(ActingRole.EMPLOYEE, stored.getActingRole());
        assertEquals(AssignmentStatus.ACTIVE, stored.getStatus());
        assertEquals(EMPLOYEE, stored.getEmployeeRef());
        assertEquals(ADMIN, stored.getAssignedBy());
        assertEquals(1, counter(campaign));

        List<ConvocatoriaAuditEntry> entries = audit(campaign);
        assertEquals(List.of(ConvocatoriaAuditAction.CONVOCATORIA_CREATED, ConvocatoriaAuditAction.EMPLOYEE_ASSIGNED),
                entries.stream().map(ConvocatoriaAuditEntry::action).toList());
        assertEquals(EMPLOYEE, entries.get(1).targetRef());
        assertFalse(entries.get(1).selfAssigned());
        assertEquals(commandId, entries.get(1).commandId());
    }

    @Test
    void duplicateCommandIdHasSingleEffectAndSameResult() {
        String commandId = newCommandId();
        AssignmentResult first = assign(commandId, ADMIN, campaign, EMPLOYEE);
        AssignmentResult second = assign(commandId, ADMIN, campaign, EMPLOYEE);

        assertEquals(first, second);
        assertEquals(1, count(CampaignAssignmentDocument.COLLECTION));
        assertEquals(1, counter(campaign));
        assertEquals(2, audit(campaign).size());
    }

    @Test
    void employeeAlreadyActiveElsewhereIsRejected() {
        String other = ConvocatoriaScenarios.createConvocatoria(lifecycle, ADMIN, ORG, flexible());
        assign(newCommandId(), ADMIN, other, EMPLOYEE);
        clearInvocations(responsibleState, auditLog);
        String commandId = newCommandId();

        assertThrows(EmployeeAlreadyAssignedException.class, () -> assign(commandId, ADMIN, campaign, EMPLOYEE));

        verify(responsibleState, never()).increment(anyString());
        verify(auditLog, never()).append(any());
        assertEquals(0, counter(campaign));
        assertEquals(1, audit(campaign).size());
        assertFalse(processedCommands.find(commandId).isPresent());
    }

    @Test
    void sameEmployeeTwiceInSameCampaignIsRejected() {
        assign(newCommandId(), ADMIN, campaign, ADMIN_EMPLOYEE);
        clearInvocations(assignments, responsibleState, auditLog);
        assertThrows(ResponsibleAlreadyActiveInCampaignException.class,
                () -> assign(newCommandId(), ADMIN, campaign, ADMIN_EMPLOYEE));
        assertNoWrites();
        assertEquals(1, counter(campaign));
    }

    @Test
    void recipientMustBeEmployeeOfTheSameOrganization() {
        assertThrows(InvalidResponsibleRecipientException.class, () -> assign(newCommandId(), ADMIN, campaign, ADMIN_2));
        assertThrows(InvalidResponsibleRecipientException.class,
                () -> assign(newCommandId(), ADMIN, campaign, REPRESENTATIVE));
        assertThrows(InvalidResponsibleRecipientException.class,
                () -> assign(newCommandId(), ADMIN, campaign, OTHER_ORG_EMPLOYEE));
        assertNoWrites();
        assertEquals(1, processedCommandCount(), "only the creation command was registered");
    }

    @Test
    void unknownRecipientExceptionFromIdentityPortPropagatesUnchanged() {
        assertThrows(FakeIdentityPrincipalPort.UnknownAccountException.class,
                () -> assign(newCommandId(), ADMIN, campaign, "ghost"));
        assertNoWrites();
    }

    @Test
    void employeeNeverSelfAssigns() {
        assertThrows(EmployeeSelfAssignmentNotAllowedException.class,
                () -> assign(newCommandId(), ADMIN_EMPLOYEE, campaign, ADMIN_EMPLOYEE));
        assertNoWrites();
    }

    @Test
    void onlyAdministratorOfTheOrganizationCanAssign() {
        assertThrows(ActorRoleNotAllowedException.class, () -> assign(newCommandId(), EMPLOYEE_2, campaign, EMPLOYEE));
        assertThrows(ActorRoleNotAllowedException.class,
                () -> assign(newCommandId(), REPRESENTATIVE, campaign, EMPLOYEE));
        assertThrows(ActorNotInCampaignOrganizationException.class,
                () -> assign(newCommandId(), OTHER_ORG_ADMIN, campaign, EMPLOYEE));
        assertNoWrites();
    }

    @Test
    void forcedFailureRollsBackAssignmentCounterAuditAndClaim() {
        doThrow(new IllegalStateException("forced")).when(responsibleState).increment(anyString());
        String commandId = newCommandId();

        assertThrows(IllegalStateException.class, () -> assign(commandId, ADMIN, campaign, EMPLOYEE));

        assertEquals(0, count(CampaignAssignmentDocument.COLLECTION));
        assertEquals(0, counter(campaign));
        assertEquals(1, audit(campaign).size());
        assertFalse(processedCommands.find(commandId).isPresent());
    }

    @Test
    void sameEmployeeAssignedToTwoCampaignsConcurrentlyLeavesOneActive() throws Exception {
        String other = ConvocatoriaScenarios.createConvocatoria(lifecycle, ADMIN, ORG, flexible());
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Callable<AssignmentResult>> tasks = List.of(
                () -> { barrier.await(10, TimeUnit.SECONDS); return assign(newCommandId(), ADMIN, campaign, EMPLOYEE); },
                () -> { barrier.await(10, TimeUnit.SECONDS); return assign(newCommandId(), ADMIN, other, EMPLOYEE); });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int applied = 0;
        int rejected = 0;
        try {
            for (Future<AssignmentResult> f : pool.invokeAll(tasks)) {
                try {
                    f.get();
                    applied++;
                } catch (ExecutionException e) {
                    assertInstanceOf(EmployeeAlreadyAssignedException.class, e.getCause());
                    rejected++;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, applied);
        assertEquals(1, rejected);
        assertEquals(1, count(CampaignAssignmentDocument.COLLECTION));
        assertEquals(1, counter(campaign) + counter(other));
    }
}
