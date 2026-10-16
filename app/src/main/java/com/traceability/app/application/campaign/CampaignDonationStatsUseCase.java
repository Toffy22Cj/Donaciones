package com.traceability.app.application.campaign;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.convocatoria.application.query.CampaignDonationStats;
import com.traceability.convocatoria.application.query.CampaignDonationStatsQuery;
import com.traceability.convocatoria.domain.exception.ActorNotInCampaignOrganizationException;
import com.traceability.convocatoria.domain.exception.ActorRoleNotAllowedException;
import com.traceability.convocatoria.domain.exception.CampaignNotFoundException;
import org.springframework.stereotype.Service;

import java.time.ZoneId;

/**
 * Estadística de donaciones de una convocatoria, para el gráfico "Cómo han llegado las donaciones". Solo lectura y con
 * el mismo acceso que la predicción: {@code ADMINISTRATOR} o {@code REPRESENTATIVE} de la organización dueña,
 * comprobado en el servidor. Otra organización, una convocatoria inexistente o de otra organización y un rol
 * insuficiente dan el mismo 403 (DD-01). Un donante nunca la ve.
 */
@Service
public class CampaignDonationStatsUseCase {

    private final CampaignDonationStatsQuery stats;

    public CampaignDonationStatsUseCase(CampaignDonationStatsQuery stats) {
        this.stats = stats;
    }

    public CampaignDonationStats stats(AuthorizationPrincipal principal, String organizationId, String campaignRef,
                                       ZoneId zone) {
        if (principal.organizationId() == null || !principal.organizationId().equals(organizationId)) {
            throw new ActorNotInCampaignOrganizationException("Actor not in organization");
        }
        if (principal.roles() == null || !(principal.roles().contains(AuthorizationRole.ADMINISTRATOR)
                || principal.roles().contains(AuthorizationRole.REPRESENTATIVE))) {
            throw new ActorRoleNotAllowedException("Donation stats require ADMINISTRATOR or REPRESENTATIVE");
        }
        return stats.statsOf(organizationId, campaignRef, zone)
                .orElseThrow(() -> new CampaignNotFoundException("Campaign not found"));
    }
}
