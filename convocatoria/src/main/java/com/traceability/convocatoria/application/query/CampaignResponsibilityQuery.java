package com.traceability.convocatoria.application.query;

import com.traceability.contracts.campaign.CampaignResponsibilityPort;
import com.traceability.convocatoria.application.port.out.CampaignAssignmentRepositoryPort;
import com.traceability.convocatoria.application.port.out.ConvocatoriaRepositoryPort;
import com.traceability.convocatoria.domain.model.ConvocatoriaStatus;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.TreeSet;

/**
 * Implementación de {@link CampaignResponsibilityPort} (ADR-049 D9): papeles con los que un miembro es responsable
 * activo de convocatorias no cerradas de su organización. La consulta {@code identity} antes de quitar o degradar a un
 * miembro (regla de al menos un responsable, ADR-037 §2.5). Solo lectura, sin autorización propia: la decide quien
 * llama.
 */
@Component
public class CampaignResponsibilityQuery implements CampaignResponsibilityPort {

    private final CampaignAssignmentRepositoryPort assignments;
    private final ConvocatoriaRepositoryPort convocatorias;

    public CampaignResponsibilityQuery(CampaignAssignmentRepositoryPort assignments, ConvocatoriaRepositoryPort convocatorias) {
        this.assignments = assignments;
        this.convocatorias = convocatorias;
    }

    @Override
    public Set<String> activeActingRoles(String organizationId, String accountId) {
        Set<String> roles = new TreeSet<>();
        assignments.findActiveByResponsible(accountId).forEach(a -> convocatorias.findByCampaignRef(a.getCampaignRef())
                .filter(c -> organizationId.equals(c.getOrganizationRef()))
                .filter(c -> c.getStatus() != ConvocatoriaStatus.CLOSED)
                .ifPresent(c -> roles.add(a.getActingRole().name())));
        return roles;
    }
}
