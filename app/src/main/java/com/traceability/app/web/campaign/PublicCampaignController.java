package com.traceability.app.web.campaign;

import com.traceability.app.web.campaign.CampaignDtos.PublicCampaignResponse;
import com.traceability.contracts.organization.OrganizationPublicNamePort;
import com.traceability.convocatoria.application.query.ConvocatoriaReadPort;
import com.traceability.convocatoria.application.query.PublicCampaignView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * CV-07: detalle público por {@code publicCode}, sin JWT (ya en {@code PublicRoutes}). Plan B6-a §2.3. El código es un
 * secreto bearer: este controlador no lo registra en ningún log ni lo devuelve.
 */
@RestController
public class PublicCampaignController {

    private final ConvocatoriaReadPort campaigns;
    private final OrganizationPublicNamePort organizationNames;

    public PublicCampaignController(ConvocatoriaReadPort campaigns, OrganizationPublicNamePort organizationNames) {
        this.campaigns = campaigns;
        this.organizationNames = organizationNames;
    }

    @GetMapping("/api/v1/public/campaigns/{publicCode}")
    public PublicCampaignResponse detail(@PathVariable("publicCode") String publicCode) {
        PublicCampaignView v = campaigns.findPublicByCode(publicCode).orElseThrow(PublicCampaignNotFoundException::new);
        return new PublicCampaignResponse(organizationNames.findPublicName(v.organizationRef()).orElse(null),
                v.title(), v.description(), v.status(), v.startDate().toString(), v.endDate().toString(),
                v.acceptedDonationTypes(), v.acceptedPaymentMethods(), v.currency(),
                v.targetAmount() == null ? null : v.targetAmount().toString(),
                v.clearedAmount() == null ? null : v.clearedAmount().toString());
    }
}
