package com.traceability.convocatoria.application.service;

import com.traceability.contracts.campaign.CampaignInKindEligibilityPort;
import com.traceability.contracts.campaign.InKindEligibility;
import com.traceability.convocatoria.application.port.out.ConvocatoriaRepositoryPort;
import com.traceability.convocatoria.domain.model.Convocatoria;
import com.traceability.convocatoria.domain.model.ConvocatoriaStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Implementación de {@link CampaignInKindEligibilityPort} (ADR-029 Enmienda 1, D3; plan-d-campaign.md §3.3): dice a
 * {@code core} si una convocatoria admite ahora una donación en especie de una organización. Solo lectura y sin
 * transacción propia: la ventana entre esta consulta y la escritura del activo en {@code core} es el riesgo aceptado
 * de la enmienda. {@code OPEN} se evalúa en el momento de la consulta (Q5): una convocatoria cerrada no admite activos
 * nuevos (limitación conocida).
 * <p>
 * Va en la capa de aplicación, con el mismo patrón que {@code IdentityPrincipalPortImpl} en {@code identity}: usa el
 * puerto de persistencia del módulo y el modelo de dominio, no Mongo directamente.
 */
@Service
public class CampaignInKindEligibilityService implements CampaignInKindEligibilityPort {

    private final ConvocatoriaRepositoryPort convocatorias;

    public CampaignInKindEligibilityService(ConvocatoriaRepositoryPort convocatorias) {
        this.convocatorias = convocatorias;
    }

    @Override
    public InKindEligibility checkInKindEligibility(String campaignRef, String organizationRef) {
        Optional<Convocatoria> found = campaignRef == null ? Optional.empty() : convocatorias.findByCampaignRef(campaignRef);
        if (found.isEmpty()) {
            return InKindEligibility.CAMPAIGN_NOT_FOUND;
        }
        Convocatoria convocatoria = found.get();
        if (!convocatoria.getOrganizationRef().equals(organizationRef)) {
            return InKindEligibility.OTHER_ORGANIZATION;
        }
        if (convocatoria.getStatus() != ConvocatoriaStatus.OPEN) {
            return InKindEligibility.CAMPAIGN_CLOSED;
        }
        if (!convocatoria.getConfiguration().acceptsInKind()) {
            return InKindEligibility.IN_KIND_NOT_ACCEPTED;
        }
        return InKindEligibility.ELIGIBLE;
    }
}
