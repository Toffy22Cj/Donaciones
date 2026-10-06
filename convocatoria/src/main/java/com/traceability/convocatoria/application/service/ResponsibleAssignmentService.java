package com.traceability.convocatoria.application.service;

import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.convocatoria.application.audit.ConvocatoriaAuditAction;
import com.traceability.convocatoria.application.audit.ConvocatoriaAuditEntry;
import com.traceability.convocatoria.application.authorization.ConvocatoriaActor;
import com.traceability.convocatoria.application.authorization.ConvocatoriaAuthorizationPolicy;
import com.traceability.convocatoria.application.command.AssignEmployeeToCampaignCommand;
import com.traceability.convocatoria.application.command.AssignmentResult;
import com.traceability.convocatoria.application.command.DesignateAdministratorAsCampaignResponsibleCommand;
import com.traceability.convocatoria.application.command.RemoveResponsibleCommand;
import com.traceability.convocatoria.application.command.RemoveResponsibleResult;
import com.traceability.convocatoria.application.idempotency.CommandType;
import com.traceability.convocatoria.application.idempotency.IdempotentCommandExecutor;
import com.traceability.convocatoria.application.port.out.CampaignAssignmentRepositoryPort;
import com.traceability.convocatoria.application.port.out.CampaignResponsibleStatePort;
import com.traceability.convocatoria.application.port.out.ConvocatoriaAuditLogPort;
import com.traceability.convocatoria.application.port.out.ConvocatoriaRepositoryPort;
import com.traceability.convocatoria.domain.exception.AssignmentAlreadyRemovedException;
import com.traceability.convocatoria.domain.exception.CampaignNotFoundException;
import com.traceability.convocatoria.domain.exception.LastResponsibleRemovalWithoutReplacementException;
import com.traceability.convocatoria.domain.exception.ReplacementActingRoleRequiredException;
import com.traceability.convocatoria.domain.exception.ResponsibleAlreadyActiveInCampaignException;
import com.traceability.convocatoria.domain.exception.ResponsibleAssignmentNotFoundException;
import com.traceability.convocatoria.domain.model.ActingRole;
import com.traceability.convocatoria.domain.model.CampaignAssignment;
import com.traceability.convocatoria.domain.model.Convocatoria;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Responsables de la convocatoria: {@code AssignEmployeeToCampaign}, {@code DesignateAdministratorAsCampaignResponsible}
 * y {@code RemoveResponsible} (ADR-037 §2.4, §2.5, §5; Enmienda §4.1–§4.3; implementation_plan.md §4.4, §5, §6).
 * Dos operaciones separadas que comparten infraestructura interna; cada una con su invariante y autorización.
 * Asignación + contador + audit log + registro de comando en una sola transacción. El contador se inicializa con
 * {@code upsert} en la primera asignación (R2) y protege el retiro con {@code $gt: 1}. Una persona tiene como mucho
 * una asignación activa por convocatoria (G3); la escritura del contador serializa esa comprobación.
 */
@Service
public class ResponsibleAssignmentService {

    private final IdempotentCommandExecutor executor;
    private final ConvocatoriaAuthorizationPolicy authorizationPolicy;
    private final ConvocatoriaRepositoryPort convocatorias;
    private final CampaignAssignmentRepositoryPort assignments;
    private final CampaignResponsibleStatePort responsibleState;
    private final ConvocatoriaAuditLogPort auditLog;
    private final Clock clock;

    public ResponsibleAssignmentService(IdempotentCommandExecutor executor,
                                        ConvocatoriaAuthorizationPolicy authorizationPolicy,
                                        ConvocatoriaRepositoryPort convocatorias,
                                        CampaignAssignmentRepositoryPort assignments,
                                        CampaignResponsibleStatePort responsibleState,
                                        ConvocatoriaAuditLogPort auditLog,
                                        ObjectProvider<Clock> clock) {
        this.executor = executor;
        this.authorizationPolicy = authorizationPolicy;
        this.convocatorias = convocatorias;
        this.assignments = assignments;
        this.responsibleState = responsibleState;
        this.auditLog = auditLog;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /** {@code ADMINISTRATOR} asigna a un {@code EMPLOYEE} de la misma organización; sin autoasignación (Enmienda §4.1, §4.3). */
    public AssignmentResult assignEmployee(AssignEmployeeToCampaignCommand command) {
        Convocatoria convocatoria = load(command.campaignRef());
        ConvocatoriaActor actor = authorizationPolicy.requireAdministratorOf(command.actorAccountId(),
                convocatoria.getOrganizationRef());
        Map<String, String> result = executor.execute(command.commandId(), CommandType.ASSIGN_EMPLOYEE_TO_CAMPAIGN, () -> {
            CampaignAssignment assignment = insertResponsible(convocatoria, command.employeeRef(), ActingRole.EMPLOYEE,
                    actor, command.commandId());
            responsibleState.increment(convocatoria.getCampaignRef());
            return Map.of("assignmentId", assignment.getAssignmentId());
        });
        return new AssignmentResult(result.get("assignmentId"));
    }

    /**
     * {@code ADMINISTRATOR} designa a un {@code ADMINISTRATOR} de la misma organización; sin límite de convocatorias;
     * autoasignación permitida y marcada en el audit log (Enmienda §4.1–§4.3).
     */
    public AssignmentResult designateAdministrator(DesignateAdministratorAsCampaignResponsibleCommand command) {
        Convocatoria convocatoria = load(command.campaignRef());
        ConvocatoriaActor actor = authorizationPolicy.requireAdministratorOf(command.actorAccountId(),
                convocatoria.getOrganizationRef());
        Map<String, String> result = executor.execute(command.commandId(),
                CommandType.DESIGNATE_ADMINISTRATOR_AS_CAMPAIGN_RESPONSIBLE, () -> {
                    CampaignAssignment assignment = insertResponsible(convocatoria, command.administratorRef(),
                            ActingRole.ADMINISTRATOR, actor, command.commandId());
                    responsibleState.increment(convocatoria.getCampaignRef());
                    return Map.of("assignmentId", assignment.getAssignmentId());
                });
        return new AssignmentResult(result.get("assignmentId"));
    }

    /**
     * Una sola operación (ADR-037 §2.5): sin reemplazo, {@code $gt: 1} sobre el contador
     * ({@link LastResponsibleRemovalWithoutReplacementException}); con reemplazo, misma transacción y contador sin
     * cambio, aplicando al reemplazo las reglas de la operación de su {@code actingRole} (Enmienda §4.2; G2).
     */
    public RemoveResponsibleResult removeResponsible(RemoveResponsibleCommand command) {
        Convocatoria convocatoria = load(command.campaignRef());
        ConvocatoriaActor actor = authorizationPolicy.requireAdministratorOf(command.actorAccountId(),
                convocatoria.getOrganizationRef());
        if (command.replacementRef() != null && command.replacementActingRole() == null) {
            throw new ReplacementActingRoleRequiredException("replacementActingRole is required with a replacement");
        }
        Map<String, String> result = executor.execute(command.commandId(), CommandType.REMOVE_RESPONSIBLE, () -> {
            List<CampaignAssignment> active = assignments.findActiveByCampaignRefAndResponsible(
                    convocatoria.getCampaignRef(), command.responsibleRef());
            if (active.isEmpty()) {
                throw new ResponsibleAssignmentNotFoundException("No active assignment of " + command.responsibleRef()
                        + " in campaign " + convocatoria.getCampaignRef());
            }
            CampaignAssignment removed = active.get(0);
            Instant now = clock.instant();
            removed.remove(now);
            if (!assignments.markRemovedIfActive(removed.getAssignmentId(), now)) {
                throw new AssignmentAlreadyRemovedException("Assignment " + removed.getAssignmentId() + " is already REMOVED");
            }
            String replacementAssignmentId = null;
            if (command.replacementRef() == null) {
                if (!responsibleState.decrementIfMoreThanOne(convocatoria.getCampaignRef())) {
                    throw new LastResponsibleRemovalWithoutReplacementException("Removing " + command.responsibleRef()
                            + " would leave campaign " + convocatoria.getCampaignRef() + " without responsibles");
                }
            }
            Map<String, String> details = new HashMap<>();
            details.put("removedAssignmentId", removed.getAssignmentId());
            details.put("actingRole", removed.getActingRole().name());
            if (command.replacementRef() != null) {
                details.put("replacementRef", command.replacementRef());
                details.put("replacementActingRole", command.replacementActingRole().name());
            }
            audit(ConvocatoriaAuditAction.RESPONSIBLE_REMOVED, convocatoria.getCampaignRef(), actor,
                    command.responsibleRef(), false, command.commandId(), details);
            if (command.replacementRef() != null) {
                replacementAssignmentId = insertResponsible(convocatoria, command.replacementRef(),
                        command.replacementActingRole(), actor, command.commandId()).getAssignmentId();
            }
            Map<String, String> out = new HashMap<>();
            out.put("removedAssignmentId", removed.getAssignmentId());
            if (replacementAssignmentId != null) {
                out.put("replacementAssignmentId", replacementAssignmentId);
            }
            return out;
        });
        return new RemoveResponsibleResult(result.get("removedAssignmentId"), result.get("replacementAssignmentId"));
    }

    /** Reglas comunes de inserción de un responsable según su operación (X2; Enmienda §4.2, §4.3; G3). */
    private CampaignAssignment insertResponsible(Convocatoria convocatoria, String responsibleRef, ActingRole actingRole,
                                                 ConvocatoriaActor actor, String commandId) {
        AuthorizationRole requiredRole = actingRole == ActingRole.EMPLOYEE
                ? AuthorizationRole.EMPLOYEE : AuthorizationRole.ADMINISTRATOR;
        authorizationPolicy.requireRecipient(responsibleRef, convocatoria.getOrganizationRef(), requiredRole);
        Instant now = clock.instant();
        String assignmentId = UUID.randomUUID().toString();
        CampaignAssignment assignment = actingRole == ActingRole.EMPLOYEE
                ? CampaignAssignment.assignEmployee(assignmentId, convocatoria.getCampaignRef(), responsibleRef,
                        actor.accountId(), now)
                : CampaignAssignment.designateAdministrator(assignmentId, convocatoria.getCampaignRef(), responsibleRef,
                        actor.accountId(), now);
        if (!assignments.findActiveByCampaignRefAndResponsible(convocatoria.getCampaignRef(), responsibleRef).isEmpty()) {
            throw new ResponsibleAlreadyActiveInCampaignException(responsibleRef
                    + " is already an active responsible of campaign " + convocatoria.getCampaignRef());
        }
        assignments.insert(assignment);
        audit(actingRole == ActingRole.EMPLOYEE ? ConvocatoriaAuditAction.EMPLOYEE_ASSIGNED
                        : ConvocatoriaAuditAction.ADMINISTRATOR_DESIGNATED,
                convocatoria.getCampaignRef(), actor, responsibleRef, assignment.isSelfAssigned(), commandId,
                Map.of("assignmentId", assignmentId, "actingRole", actingRole.name()));
        return assignment;
    }

    private Convocatoria load(String campaignRef) {
        return convocatorias.findByCampaignRef(campaignRef)
                .orElseThrow(() -> new CampaignNotFoundException("Campaign " + campaignRef + " not found"));
    }

    private void audit(ConvocatoriaAuditAction action, String campaignRef, ConvocatoriaActor actor, String targetRef,
                       boolean selfAssigned, String commandId, Map<String, String> details) {
        auditLog.append(new ConvocatoriaAuditEntry(UUID.randomUUID().toString(), action, campaignRef,
                actor.accountId(), targetRef, selfAssigned, commandId, clock.instant(), details));
    }
}
