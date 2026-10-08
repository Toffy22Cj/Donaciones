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

    /** Asignaciones {@code ACTIVE} de un responsable en cualquier convocatoria (ADR-049 D9; "mis convocatorias"). */
    List<CampaignAssignment> findActiveByResponsible(String responsibleRef);

    /** {@code ACTIVE} o {@code HISTORICAL} (cerradas con el responsable puesto): "mis convocatorias" (DD-72, D-06). */
    List<CampaignAssignment> findActiveOrHistoricalByResponsible(String responsibleRef);

    Optional<CampaignAssignment> findById(String assignmentId);

    /**
     * Al cerrar la convocatoria (D-06): todas sus asignaciones {@code ACTIVE → HISTORICAL}, con {@code removedAt} =
     * {@code closedAt}. Devuelve cuántas.
     */
    long markHistoricalByCampaignRef(String campaignRef, Instant closedAt);

    /** Escritura condicional {@code ACTIVE → REMOVED}. Devuelve si se aplicó. */
    boolean markRemovedIfActive(String assignmentId, Instant removedAt);
}
