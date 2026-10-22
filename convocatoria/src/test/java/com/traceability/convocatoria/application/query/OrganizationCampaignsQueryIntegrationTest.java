package com.traceability.convocatoria.application.query;

import com.traceability.convocatoria.application.query.OrganizationCampaignsQuery.AdminCampaignView;
import com.traceability.convocatoria.application.service.ConvocatoriaLifecycleService;
import com.traceability.convocatoria.support.AbstractConvocatoriaServiceIntegrationTest;
import com.traceability.convocatoria.support.ConvocatoriaScenarios;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** Ficha R-6, G-5: la lista de la organización lleva las fechas de inicio y fin del dominio. */
class OrganizationCampaignsQueryIntegrationTest extends AbstractConvocatoriaServiceIntegrationTest {

    @Autowired private ConvocatoriaLifecycleService lifecycle;
    @Autowired private OrganizationCampaignsQuery query;

    @Test
    void eachCampaign_carriesItsStartAndEndDates() {
        String campaignRef = ConvocatoriaScenarios.createConvocatoria(lifecycle, ADMIN, ORG, flexible());

        AdminCampaignView view = query.list(ADMIN, ORG).stream()
                .filter(v -> v.campaignRef().equals(campaignRef)).findFirst().orElseThrow();

        assertThat(view.startDate()).isEqualTo(ConvocatoriaScenarios.START);
        assertThat(view.endDate()).isEqualTo(ConvocatoriaScenarios.END);
    }
}
