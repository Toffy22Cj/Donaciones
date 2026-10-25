package com.traceability.app.web.campaign;

import com.traceability.app.application.media.CampaignCoverService;
import com.traceability.app.application.media.OrganizationLogoService;
import com.traceability.app.web.campaign.CampaignDtos.PublicCampaignResponse;
import com.traceability.convocatoria.application.query.ConvocatoriaReadPort;
import com.traceability.convocatoria.application.query.PublicCampaignView;
import identity.application.service.OrganizationLogoReferenceQuery;
import identity.application.service.OrganizationLogoReferenceQuery.PublicOrganization;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * CV-07: detalle público por {@code publicCode}, sin JWT (ya en {@code PublicRoutes}). Plan B6-a §2.3. El código es un
 * secreto bearer: este controlador no lo registra en ningún log ni lo devuelve como dato, solo dentro de
 * {@code organizationLogoUrl}, que ya lo lleva quien pregunta. Nombre, verificación y logo de la organización salen de
 * una sola lectura de {@code identity} (plan de la Tarea 2 de ADR-051, P-T2-7).
 */
@RestController
public class PublicCampaignController {

    private final ConvocatoriaReadPort campaigns;
    private final OrganizationLogoReferenceQuery organizations;

    public PublicCampaignController(ConvocatoriaReadPort campaigns, OrganizationLogoReferenceQuery organizations) {
        this.campaigns = campaigns;
        this.organizations = organizations;
    }

    @GetMapping("/api/v1/public/campaigns/{publicCode}")
    public PublicCampaignResponse detail(@PathVariable("publicCode") String publicCode) {
        PublicCampaignView v = campaigns.findPublicByCode(publicCode).orElseThrow(PublicCampaignNotFoundException::new);
        PublicOrganization o = organizations.publicOrganization(v.organizationRef());
        return new PublicCampaignResponse(o.name(), o.verified(), v.title(), v.description(), v.status(),
                v.startDate().toString(), v.endDate().toString(),
                v.acceptedDonationTypes(), v.acceptedPaymentMethods(), v.currency(),
                v.targetAmount() == null ? null : v.targetAmount().toString(),
                v.clearedAmount() == null ? null : v.clearedAmount().toString(),
                o.hasLogo() ? OrganizationLogoService.publicLogoUrl(publicCode) : null,
                v.coverObjectKey() == null ? null : CampaignCoverService.coverUrl(publicCode));
    }
}
