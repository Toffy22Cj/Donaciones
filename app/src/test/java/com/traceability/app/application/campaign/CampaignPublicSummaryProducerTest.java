package com.traceability.app.application.campaign;

import com.traceability.contracts.campaign.CampaignPublicSummaryPort.CampaignPublicSummary;
import com.traceability.contracts.organization.OrganizationPublicNamePort;
import com.traceability.convocatoria.application.query.CampaignTitleQuery;
import com.traceability.convocatoria.application.query.CampaignTitleQuery.CampaignTitle;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ficha R-6, G-1; D-R6-1: el título sale de {@code convocatoria} y el nombre, de {@code identity}. */
class CampaignPublicSummaryProducerTest {

    private final CampaignTitleQuery titles = mock(CampaignTitleQuery.class);
    private final OrganizationPublicNamePort names = mock(OrganizationPublicNamePort.class);
    private final CampaignPublicSummaryProducer producer = new CampaignPublicSummaryProducer(titles, names);

    @Test
    void anExistingCampaign_givesItsTitleAndTheNameOfItsOrganization() {
        when(titles.findByCampaignRef("camp-1")).thenReturn(Optional.of(new CampaignTitle("Invierno", "org-1")));
        when(names.findPublicName("org-1")).thenReturn(Optional.of("Fundación Uno"));

        assertThat(producer.findByCampaignRef("camp-1")).contains(new CampaignPublicSummary("Invierno", "Fundación Uno"));
    }

    @Test
    void anOrganizationWithoutName_givesTheTitleWithoutName() {
        when(titles.findByCampaignRef("camp-1")).thenReturn(Optional.of(new CampaignTitle("Invierno", "org-1")));
        when(names.findPublicName("org-1")).thenReturn(Optional.empty());

        assertThat(producer.findByCampaignRef("camp-1")).contains(new CampaignPublicSummary("Invierno", null));
    }

    @Test
    void anUnknownCampaign_isEmpty_andIdentityIsNotAsked() {
        when(titles.findByCampaignRef("camp-missing")).thenReturn(Optional.empty());

        assertThat(producer.findByCampaignRef("camp-missing")).isEmpty();
        verify(names, never()).findPublicName(any());
    }
}
