package com.traceability.convocatoria.application.port.out;

import com.traceability.convocatoria.domain.model.CampaignAssignment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistencia de {@link CampaignAssignment} (ADR-037 §2.4; Enmienda §4.2). Cada asignación es una inserción,
 * protegida por el índice único parcial {@code {employeeRef:1} WHERE status=ACTIVE AND actingRole=EMPLOYEE}.
 */
public interface CampaignAssignmentRepositoryPort {

    /** Inserta; una colisión con el índice único parcial lanza {@code EmployeeAlreadyAssignedException}. */
    void insert(CampaignAssignment assignment);

    List<CampaignAssignment> findActiveByCampaignRefAndResponsible(String campaignRef, String responsibleRef);

    List<CampaignAssignment> findByCampaignRef(String campaignRef);

    Optional<CampaignAssignment> findById(String assignmentId);

    /** Escritura condicional {@code ACTIVE → REMOVED}. Devuelve si se aplicó. */
    boolean markRemovedIfActive(String assignmentId, Instant removedAt);
}
