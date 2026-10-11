package com.traceability.convocatoria.application.query;

import com.traceability.convocatoria.application.port.out.ConvocatoriaRepositoryPort;
import com.traceability.convocatoria.application.port.out.DonationIntentRepositoryPort;
import com.traceability.convocatoria.application.query.PublicDonationStats.AppliedDonation;
import com.traceability.convocatoria.application.service.ConvocatoriaLifecycleService;
import com.traceability.convocatoria.domain.model.Convocatoria;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import com.traceability.convocatoria.domain.model.DonationIntent;
import com.traceability.convocatoria.domain.model.DonationIntentStatus;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/** Lectura de solo lectura para la estadística pública de donaciones por {@code publicCode}. No escribe nada. */
@Component
public class PublicDonationStatsQuery {

    /** Tope de intenciones leídas por convocatoria, el mismo que la lectura del predictor. */
    public static final int MAX_INTENTS = CampaignPredictionDataQuery.MAX_INTENTS;

    private final ConvocatoriaRepositoryPort convocatorias;
    private final DonationIntentRepositoryPort intents;

    public PublicDonationStatsQuery(ConvocatoriaRepositoryPort convocatorias, DonationIntentRepositoryPort intents) {
        this.convocatorias = convocatorias;
        this.intents = intents;
    }

    /** Vacío si no existe o si el código no tiene la forma de uno emitido (como CV-07). */
    public Optional<PublicDonationStats> statsOf(String publicCode, ZoneId zone) {
        if (publicCode == null || !ConvocatoriaLifecycleService.PUBLIC_CODE_FORMAT.matcher(publicCode).matches()) {
            return Optional.empty();
        }
        return convocatorias.findByPublicCode(publicCode).map(c -> stats(c, zone));
    }

    private PublicDonationStats stats(Convocatoria c, ZoneId zone) {
        ConvocatoriaConfiguration config = c.getConfiguration();
        if (!config.acceptsMonetary()) {
            return PublicDonationStats.of(null, null, List.of(), zone);
        }
        List<AppliedDonation> applied = intents.findByCampaignRef(c.getCampaignRef(), MAX_INTENTS).stream()
                .filter(i -> i.getStatus() == DonationIntentStatus.CONFIRMED)
                .filter(i -> i.getApplicationTracking().fundsAppliedAt() != null)
                .map(PublicDonationStatsQuery::applied)
                .toList();
        return PublicDonationStats.of(config.currency(), config.targetAmount(), applied, zone);
    }

    private static AppliedDonation applied(DonationIntent i) {
        return new AppliedDonation(i.getApplicationTracking().fundsAppliedAt(), i.getAmount());
    }
}
