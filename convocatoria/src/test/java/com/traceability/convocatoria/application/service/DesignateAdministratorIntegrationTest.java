package com.traceability.convocatoria.application.service;

import com.traceability.convocatoria.application.audit.ConvocatoriaAuditAction;
import com.traceability.convocatoria.application.audit.ConvocatoriaAuditEntry;
import com.traceability.convocatoria.application.command.AssignEmployeeToCampaignCommand;
import com.traceability.convocatoria.application.command.AssignmentResult;
import com.traceability.convocatoria.application.command.DesignateAdministratorAsCampaignResponsibleCommand;
import com.traceability.convocatoria.domain.exception.ActorRoleNotAllowedException;
import com.traceability.convocatoria.domain.exception.InvalidResponsibleRecipientException;
import com.traceability.convocatoria.domain.exception.ResponsibleAlreadyActiveInCampaignException;
import com.traceability.convocatoria.domain.model.ActingRole;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** implementation_plan.md §5 ({@code DesignateAdministratorAsCampaignResponsible}), §6, §7.1, §12.3, §13.1; Enmienda §4. */
class DesignateAdministratorIntegrationTest extends AbstractConvocatoriaServiceIntegrationTest {

    @Autowired private ConvocatoriaLifecycleService lifecycle;
    @Autowired private ResponsibleAssignmentService service;

    private String campaign;

    @BeforeEach
    void createCampaign() {
        campaign = ConvocatoriaScenarios.createConvocatoria(lifecycle, ADMIN, ORG, flexible());
        clearInvocations(assignments, responsibleState, auditLog);
    }

    private AssignmentResult designate(String commandId, String actor, String campaignRef, String administrator) {
        return service.designateAdministrator(
                new DesignateAdministratorAsCampaignResponsibleCommand(commandId, actor, campaignRef, administrator));
    }

    private long counter(String campaignRef) {
        return responsibleState.find(campaignRef).map(s -> s.activeResponsibleCount()).orElse(0L);
    }

    @Test
    void designatesAnotherAdministratorNotSelfAssigned() {
        AssignmentResult result = designate(newCommandId(), ADMIN, campaign, ADMIN_2);
        CampaignAssignment stored = assignments.findById(result.assignmentId()).orElseThrow();
        assertEquals(ActingRole.ADMINISTRATOR, stored.getActingRole());
        assertFalse(stored.isSelfAssigned());
        assertEquals(1, counter(campaign));
        ConvocatoriaAuditEntry entry = audit(campaign).get(1);
        assertEquals(ConvocatoriaAuditAction.ADMINISTRATOR_DESIGNATED, entry.action());
        assertFalse(entry.selfAssigned());
    }

    @Test
    void selfDesignationIsAllowedAndMarkedInAuditLog() {
        AssignmentResult result = designate(newCommandId(), ADMIN, campaign, ADMIN);
        assertTrue(assignments.findById(result.assignmentId()).orElseThrow().isSelfAssigned());
        ConvocatoriaAuditEntry entry = audit(campaign).get(1);
        assertEquals(ConvocatoriaAuditAction.ADMINISTRATOR_DESIGNATED, entry.action());
        assertTrue(entry.selfAssigned());
        assertEquals(ADMIN, entry.actorRef());
        assertEquals(ADMIN, entry.targetRef());
    }

    @Test
    void administratorCanBeResponsibleOfSeveralCampaigns() {
        String other = ConvocatoriaScenarios.createConvocatoria(lifecycle, ADMIN, ORG, flexible());
        designate(newCommandId(), ADMIN, campaign, ADMIN_2);
        designate(newCommandId(), ADMIN, other, ADMIN_2);
        assertEquals(2, count(CampaignAssignmentDocument.COLLECTION));
        assertEquals(1, counter(campaign));
        assertEquals(1, counter(other));
    }

    @Test
    void administratorAlreadyActiveInSameCampaignIsRejected() {
        designate(newCommandId(), ADMIN, campaign, ADMIN_EMPLOYEE);
        clearInvocations(assignments, responsibleState, auditLog);
        assertThrows(ResponsibleAlreadyActiveInCampaignException.class,
                () -> designate(newCommandId(), ADMIN, campaign, ADMIN_EMPLOYEE));
        assertThrows(ResponsibleAlreadyActiveInCampaignException.class, () -> service.assignEmployee(
                new AssignEmployeeToCampaignCommand(newCommandId(), ADMIN, campaign, ADMIN_EMPLOYEE)));
        verify(assignments, never()).insert(any());
        verify(responsibleState, never()).increment(anyString());
        verify(auditLog, never()).append(any());
        assertEquals(1, counter(campaign));
    }

    @Test
    void recipientMustBeAdministratorOfTheSameOrganization() {
        assertThrows(InvalidResponsibleRecipientException.class, () -> designate(newCommandId(), ADMIN, campaign, EMPLOYEE));
        assertThrows(InvalidResponsibleRecipientException.class,
                () -> designate(newCommandId(), ADMIN, campaign, REPRESENTATIVE));
        assertThrows(InvalidResponsibleRecipientException.class,
                () -> designate(newCommandId(), ADMIN, campaign, OTHER_ORG_ADMIN));
        verify(assignments, never()).insert(any());
        assertEquals(0, counter(campaign));
    }

    @Test
    void onlyAdministratorCanDesignate() {
        assertThrows(ActorRoleNotAllowedException.class, () -> designate(newCommandId(), EMPLOYEE, campaign, ADMIN));
        assertThrows(ActorRoleNotAllowedException.class, () -> designate(newCommandId(), REPRESENTATIVE, campaign, ADMIN));
        verify(assignments, never()).insert(any());
    }

    @Test
    void duplicateCommandIdHasSingleEffect() {
        String commandId = newCommandId();
        assertEquals(designate(commandId, ADMIN, campaign, ADMIN), designate(commandId, ADMIN, campaign, ADMIN));
        assertEquals(1, count(CampaignAssignmentDocument.COLLECTION));
        assertEquals(1, counter(campaign));
        assertEquals(2, audit(campaign).size());
    }

    @Test
    void sameAdministratorDesignatedInTwoCampaignsConcurrentlyBothActive() throws Exception {
        String other = ConvocatoriaScenarios.createConvocatoria(lifecycle, ADMIN, ORG, flexible());
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Callable<AssignmentResult>> tasks = List.of(
                () -> { barrier.await(10, TimeUnit.SECONDS); return designate(newCommandId(), ADMIN, campaign, ADMIN_2); },
                () -> { barrier.await(10, TimeUnit.SECONDS); return designate(newCommandId(), ADMIN, other, ADMIN_2); });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (Future<AssignmentResult> f : pool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, count(CampaignAssignmentDocument.COLLECTION));
        assertEquals(1, counter(campaign));
        assertEquals(1, counter(other));
    }
}
