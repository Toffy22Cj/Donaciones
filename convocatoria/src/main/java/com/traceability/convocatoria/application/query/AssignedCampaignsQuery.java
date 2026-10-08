package com.traceability.convocatoria.application.query;

import com.traceability.convocatoria.application.port.out.CampaignAssignmentRepositoryPort;
import com.traceability.convocatoria.application.port.out.ConvocatoriaRepositoryPort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * "Mis convocatorias asignadas" (autorización (3) de Carlos, §3.4): las asignaciones {@code ACTIVE} del responsable
 * en convocatorias de <b>su organización actual</b>, con el estado de la convocatoria (también {@code CLOSED}, para
 * que vea su historial; `[DECISIÓN DELEGADA — pendiente de ratificar por Carlos]` DD-72). Solo lectura; la
 * organización la pone quien llama desde el principal, nunca el cliente. Una página con tope de {@value #MAX_ITEMS}.
 */
@Component
public class AssignedCampaignsQuery {

    public static final int MAX_ITEMS = 100;

    public record AssignedCampaign(String campaignRef, String publicCode, String title, String status,
                                   String actingRole, Instant assignedAt) {}

    private final CampaignAssignmentRepositoryPort assignments;
    private final ConvocatoriaRepositoryPort convocatorias;

    public AssignedCampaignsQuery(CampaignAssignmentRepositoryPort assignments, ConvocatoriaRepositoryPort convocatorias) {
        this.assignments = assignments;
        this.convocatorias = convocatorias;
    }

    public List<AssignedCampaign> forResponsible(String accountId, String organizationId) {
        Objects.requireNonNull(accountId, "accountId must not be null");
        if (organizationId == null) {
            return List.of();
        }
        return assignments.findActiveByResponsible(accountId).stream()
                .flatMap(a -> convocatorias.findByCampaignRef(a.getCampaignRef())
                        .filter(c -> organizationId.equals(c.getOrganizationRef()))
                        .map(c -> new AssignedCampaign(c.getCampaignRef(), c.getPublicCode(), c.getTitle(),
                                c.getStatus().name(), a.getActingRole().name(), a.getAssignedAt()))
                        .stream())
                .limit(MAX_ITEMS)
                .toList();
    }
}
