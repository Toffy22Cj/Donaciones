package com.traceability.convocatoria.infrastructure.persistence.mongo.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Documento de {@code CampaignAssignment} con el índice único parcial de Enmienda §4.2:
 * {@code { employeeRef: 1 } UNIQUE WHERE status = "ACTIVE" AND actingRole = "EMPLOYEE"}.
 */
@Document(collection = CampaignAssignmentDocument.COLLECTION)
@CompoundIndex(name = CampaignAssignmentDocument.ACTIVE_EMPLOYEE_INDEX, def = "{'employeeRef': 1}", unique = true,
        partialFilter = "{'status': 'ACTIVE', 'actingRole': 'EMPLOYEE'}")
public class CampaignAssignmentDocument {

    public static final String COLLECTION = "campaign_assignments";
    public static final String ACTIVE_EMPLOYEE_INDEX = "uq_active_employee_assignment";

    @Id
    public String assignmentId;
    public String campaignRef;
    public String employeeRef;
    public String actingRole;
    public String status;
    public Instant assignedAt;
    public Instant removedAt;
    public String assignedBy;
    public boolean selfAssigned;
}
