package com.traceability.convocatoria.application.query;

import com.traceability.convocatoria.application.port.out.ConvocatoriaRepositoryPort;
import com.traceability.convocatoria.application.port.out.DonationIntentRepositoryPort;
import com.traceability.convocatoria.application.query.CampaignPredictionData.IntentOutcome;
import com.traceability.convocatoria.application.query.CampaignPredictionData.Outcome;
import com.traceability.convocatoria.domain.model.Convocatoria;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import com.traceability.convocatoria.domain.model.DonationIntent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Lectura de solo lectura para el predictor (P3). No escribe nada. */
@Component
public class CampaignPredictionDataQuery {

    /** Tope de intenciones leídas; si se alcanza, {@code truncated} lo dice y la predicción no se sirve. */
    public static final int MAX_INTENTS = 10_000;

    private final ConvocatoriaRepositoryPort convocatorias;
    private final DonationIntentRepositoryPort intents;

    public CampaignPredictionDataQuery(ConvocatoriaRepositoryPort convocatorias, DonationIntentRepositoryPort intents) {
        this.convocatorias = convocatorias;
        this.intents = intents;
    }

    public Optional<CampaignPredictionData> dataOf(String campaignRef) {
        return convocatorias.findByCampaignRef(campaignRef).map(this::data);
    }

    private CampaignPredictionData data(Convocatoria c) {
        ConvocatoriaConfiguration config = c.getConfiguration();
        boolean monetary = config.acceptsMonetary();
        List<DonationIntent> found = intents.findByCampaignRef(c.getCampaignRef(), MAX_INTENTS + 1);
        boolean truncated = found.size() > MAX_INTENTS;
        Map<String, Integer> donorKeys = new HashMap<>();
        List<IntentOutcome> outcomes = new ArrayList<>();
        for (DonationIntent intent : found.subList(0, Math.min(found.size(), MAX_INTENTS))) {
            int donorKey = donorKeys.computeIfAbsent(intent.getDonorRef(), k -> donorKeys.size());
            Outcome outcome = switch (intent.getStatus()) {
                case CONFIRMED -> Outcome.CONFIRMED;
                case FAILED, EXPIRED_UNKNOWN -> Outcome.FAILED;
                case FUNDING_REJECTED -> Outcome.FUNDING_REJECTED;
                case PENDING -> Outcome.IN_PROGRESS;
            };
            outcomes.add(new IntentOutcome(outcome, intent.getAmount(), donorKey,
                    outcome == Outcome.CONFIRMED && intent.getConfirmation() != null
                            ? intent.getConfirmation().confirmedAt() : null));
        }
        return new CampaignPredictionData(c.getCampaignRef(), c.getOrganizationRef(), c.getStatus().name(),
                c.getVisibility().name(), monetary ? config.targetPolicy().name() : null,
                monetary ? config.targetAmount() : null, c.getStartDate(), c.getEndDate(),
                monetary ? config.acceptedPaymentMethods().size() : 0,
                convocatorias.countByOrganizationStartedBefore(c.getOrganizationRef(), c.getStartDate()),
                List.copyOf(outcomes), truncated);
    }
}
