package com.traceability.convocatoria.infrastructure.persistence.mongo.mapper;

import com.traceability.convocatoria.domain.model.ActingRole;
import com.traceability.convocatoria.domain.model.AssignmentStatus;
import com.traceability.convocatoria.domain.model.CampaignAssignment;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.CampaignAssignmentDocument;

/**
 * Mapper manual {@code CampaignAssignment} ↔ documento (implementation_plan.md §4.3).
 */
public final class CampaignAssignmentMapper {

    private CampaignAssignmentMapper() {
    }

    public static CampaignAssignmentDocument toDocument(CampaignAssignment a) {
        CampaignAssignmentDocument d = new CampaignAssignmentDocument();
        d.assignmentId = a.getAssignmentId();
        d.campaignRef = a.getCampaignRef();
        d.employeeRef = a.getEmployeeRef();
        d.actingRole = a.getActingRole().name();
        d.status = a.getStatus().name();
        d.assignedAt = a.getAssignedAt();
        d.removedAt = a.getRemovedAt();
        d.assignedBy = a.getAssignedBy();
        d.selfAssigned = a.isSelfAssigned();
        return d;
    }

    public static CampaignAssignment toDomain(CampaignAssignmentDocument d) {
        return CampaignAssignment.reconstitute(d.assignmentId, d.campaignRef, d.employeeRef,
                ActingRole.valueOf(d.actingRole), AssignmentStatus.valueOf(d.status), d.assignedAt, d.removedAt,
                d.assignedBy, d.selfAssigned);
    }
}
