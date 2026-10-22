package com.traceability.convocatoria.application.query;

import com.traceability.convocatoria.application.query.CampaignTitleQuery.CampaignTitle;
import com.traceability.convocatoria.application.service.ConvocatoriaLifecycleService;
import com.traceability.convocatoria.support.AbstractConvocatoriaServiceIntegrationTest;
import com.traceability.convocatoria.support.ConvocatoriaScenarios;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ficha R-6, G-1; plan del Bloque A, P-R6A-1 (b): título y organización de una convocatoria por su
 * {@code campaignRef}, para el seguimiento del donante. Solo lectura, sin autorización (la da el {@code trackingCode}).
 */
class CampaignTitleQueryIntegrationTest extends AbstractConvocatoriaServiceIntegrationTest {

    @Autowired private ConvocatoriaLifecycleService lifecycle;
    @Autowired private CampaignTitleQuery query;

    @Test
    void anExistingCampaign_givesItsTitleAndItsOrganization() {
        String campaignRef = ConvocatoriaScenarios.createConvocatoria(lifecycle, ADMIN, ORG, flexible());

        assertThat(query.findByCampaignRef(campaignRef)).contains(new CampaignTitle("Campaña", ORG));
    }

    @Test
    void anUnknownCampaign_isEmpty() {
        assertThat(query.findByCampaignRef("no-existe")).isEmpty();
    }
}
