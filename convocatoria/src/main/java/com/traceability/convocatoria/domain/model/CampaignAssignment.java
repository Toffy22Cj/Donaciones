package com.traceability.convocatoria.domain.model;

import com.traceability.convocatoria.domain.exception.AssignmentAlreadyRemovedException;
import com.traceability.convocatoria.domain.exception.EmployeeSelfAssignmentNotAllowedException;

import java.time.Instant;
import java.util.Objects;

/**
 * Asignación de responsable de convocatoria (ADR-037 §2.4; Enmienda §4.1–§4.3).
 * Cada asignación es una inserción; {@code REMOVED} es histórico y no se reactiva.
 * {@code employeeRef} es el nombre heredado del índice de ADR-037 §2.4 e identifica al responsable,
 * sea {@code EMPLOYEE} o {@code ADMINISTRATOR} (Enmienda §4.2 [REQUISITO]).
 */
public final class CampaignAssignment {

    private final String assignmentId;
    private final String campaignRef;
    private final String employeeRef;
    private final ActingRole actingRole;
    private AssignmentStatus status;
    private final Instant assignedAt;
    private Instant removedAt;
    private final String assignedBy;
    private final boolean selfAssigned;

    private CampaignAssignment(String assignmentId, String campaignRef, String employeeRef, ActingRole actingRole,
                               AssignmentStatus status, Instant assignedAt, Instant removedAt, String assignedBy,
                               boolean selfAssigned) {
        this.assignmentId = Objects.requireNonNull(assignmentId, "assignmentId");
        this.campaignRef = Objects.requireNonNull(campaignRef, "campaignRef");
        this.employeeRef = Objects.requireNonNull(employeeRef, "employeeRef");
        this.actingRole = Objects.requireNonNull(actingRole, "actingRole");
        this.status = Objects.requireNonNull(status, "status");
        this.assignedAt = Objects.requireNonNull(assignedAt, "assignedAt");
        this.removedAt = removedAt;
        this.assignedBy = Objects.requireNonNull(assignedBy, "assignedBy");
        this.selfAssigned = selfAssigned;
    }

    /** {@code AssignEmployeeToCampaign}: {@code actingRole = EMPLOYEE}; un {@code EMPLOYEE} nunca se autoasigna (Enmienda §4.3). */
    public static CampaignAssignment assignEmployee(String assignmentId, String campaignRef, String employeeRef,
                                                    String assignedBy, Instant assignedAt) {
        if (employeeRef.equals(assignedBy)) {
            throw new EmployeeSelfAssignmentNotAllowedException("An EMPLOYEE responsible cannot be self-assigned");
        }
        return new CampaignAssignment(assignmentId, campaignRef, employeeRef, ActingRole.EMPLOYEE,
                AssignmentStatus.ACTIVE, assignedAt, null, assignedBy, false);
    }

    /**
     * {@code DesignateAdministratorAsCampaignResponsible}: {@code actingRole = ADMINISTRATOR}; la autoasignación
     * está permitida y queda marcada para el audit log (Enmienda §4.3).
     */
    public static CampaignAssignment designateAdministrator(String assignmentId, String campaignRef,
                                                            String administratorRef, String assignedBy,
                                                            Instant assignedAt) {
        return new CampaignAssignment(assignmentId, campaignRef, administratorRef, ActingRole.ADMINISTRATOR,
                AssignmentStatus.ACTIVE, assignedAt, null, assignedBy, administratorRef.equals(assignedBy));
    }

    /** Uso exclusivo de adaptadores de persistencia. */
    public static CampaignAssignment reconstitute(String assignmentId, String campaignRef, String employeeRef,
                                                  ActingRole actingRole, AssignmentStatus status, Instant assignedAt,
                                                  Instant removedAt, String assignedBy, boolean selfAssigned) {
        return new CampaignAssignment(assignmentId, campaignRef, employeeRef, actingRole, status, assignedAt,
                removedAt, assignedBy, selfAssigned);
    }

    /** {@code ACTIVE → REMOVED}; sin reactivación (ADR-037 §2.4, §7). */
    public void remove(Instant at) {
        if (status == AssignmentStatus.REMOVED) {
            throw new AssignmentAlreadyRemovedException("Assignment " + assignmentId + " is already REMOVED");
        }
        this.status = AssignmentStatus.REMOVED;
        this.removedAt = Objects.requireNonNull(at, "at");
    }

    public String getAssignmentId() { return assignmentId; }
    public String getCampaignRef() { return campaignRef; }
    public String getEmployeeRef() { return employeeRef; }
    public ActingRole getActingRole() { return actingRole; }
    public AssignmentStatus getStatus() { return status; }
    public Instant getAssignedAt() { return assignedAt; }
    public Instant getRemovedAt() { return removedAt; }
    public String getAssignedBy() { return assignedBy; }
    public boolean isSelfAssigned() { return selfAssigned; }
}
