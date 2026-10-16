package com.traceability.convocatoria.application.query;

import com.traceability.convocatoria.application.port.out.ConvocatoriaRepositoryPort;
import com.traceability.convocatoria.application.port.out.DonationIntentRepositoryPort;
import com.traceability.convocatoria.application.query.CampaignDonationStats.AppliedDonation;
import com.traceability.convocatoria.domain.model.Convocatoria;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import com.traceability.convocatoria.domain.model.DonationIntent;
import com.traceability.convocatoria.domain.model.DonationIntentStatus;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Lectura de solo lectura para la estadística de donaciones de una convocatoria por {@code campaignRef}. No escribe
 * nada. La autorización (solo la organización dueña) la comprueba quien la llama.
 */
@Component
public class CampaignDonationStatsQuery {

    /** Tope de intenciones leídas por convocatoria, el mismo que la lectura del predictor. */
    public static final int MAX_INTENTS = CampaignPredictionDataQuery.MAX_INTENTS;

    private final ConvocatoriaRepositoryPort convocatorias;
    private final DonationIntentRepositoryPort intents;

    public CampaignDonationStatsQuery(ConvocatoriaRepositoryPort convocatorias, DonationIntentRepositoryPort intents) {
        this.convocatorias = convocatorias;
        this.intents = intents;
    }

    /** Vacío si la convocatoria no existe o no es de {@code organizationRef}. */
    public Optional<CampaignDonationStats> statsOf(String organizationRef, String campaignRef, ZoneId zone) {
        return convocatorias.findByCampaignRef(campaignRef)
                .filter(c -> c.getOrganizationRef().equals(organizationRef))
                .map(c -> stats(c, zone));
    }

    private CampaignDonationStats stats(Convocatoria c, ZoneId zone) {
        ConvocatoriaConfiguration config = c.getConfiguration();
        if (!config.acceptsMonetary()) {
            return CampaignDonationStats.of(null, null, List.of(), zone);
        }
        List<AppliedDonation> applied = intents.findByCampaignRef(c.getCampaignRef(), MAX_INTENTS).stream()
                .filter(i -> i.getStatus() == DonationIntentStatus.CONFIRMED)
                .filter(i -> i.getApplicationTracking().fundsAppliedAt() != null)
                .map(CampaignDonationStatsQuery::applied)
                .toList();
        return CampaignDonationStats.of(config.currency(), config.targetAmount(), applied, zone);
    }

    private static AppliedDonation applied(DonationIntent i) {
        return new AppliedDonation(i.getApplicationTracking().fundsAppliedAt(), i.getAmount());
    }
}
