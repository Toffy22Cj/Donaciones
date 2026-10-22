package com.traceability.app.application.campaign;

import com.traceability.contracts.campaign.CampaignPublicSummaryPort;
import com.traceability.contracts.organization.OrganizationPublicNamePort;
import com.traceability.convocatoria.application.query.CampaignTitleQuery;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Productor de {@link CampaignPublicSummaryPort} (ficha R-6, G-1; D-R6-1; plan del Bloque A, P-R6A-3): el título sale
 * de {@code convocatoria} ({@link CampaignTitleQuery}) y el nombre público de la organización, de {@code identity}
 * ({@link OrganizationPublicNamePort}). El {@code organizationRef} no sale de aquí.
 */
@Component
public class CampaignPublicSummaryProducer implements CampaignPublicSummaryPort {

    private final CampaignTitleQuery titles;
    private final OrganizationPublicNamePort organizationNames;

    public CampaignPublicSummaryProducer(CampaignTitleQuery titles, OrganizationPublicNamePort organizationNames) {
        this.titles = titles;
        this.organizationNames = organizationNames;
    }

    @Override
    public Optional<CampaignPublicSummary> findByCampaignRef(String campaignRef) {
        return titles.findByCampaignRef(campaignRef).map(t -> new CampaignPublicSummary(t.title(),
                organizationNames.findPublicName(t.organizationRef()).orElse(null)));
    }
}
