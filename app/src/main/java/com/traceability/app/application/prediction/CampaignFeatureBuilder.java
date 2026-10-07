package com.traceability.app.application.prediction;

import com.traceability.convocatoria.application.query.CampaignPredictionData;
import com.traceability.convocatoria.application.query.CampaignPredictionData.IntentOutcome;
import com.traceability.convocatoria.application.query.CampaignPredictionData.Outcome;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Variables de una convocatoria real en el instante {@code now}, con la misma definición que {@code build_snapshot} del
 * entrenamiento ({@code paxfide-predictor/generate_synthetic_dataset.py}). Correspondencia de estados
 * ({@code [DECISIÓN DELEGADA — pendiente de ratificar por Carlos]} DD-46):
 * <ul>
 *   <li>{@code CONFIRMED} = donación confirmada que entra en lo recaudado, en su {@code confirmedAt};</li>
 *   <li>{@code FUNDING_REJECTED} = confirmada pero rechazada por la política (como {@code rejectedByPolicy}): cuenta como
 *   intento, no como fallo ni como recaudado;</li>
 *   <li>{@code FAILED} y {@code EXPIRED_UNKNOWN} = intento fallido;</li>
 *   <li>{@code PENDING} = aún en curso: no cuenta todavía.</li>
 * </ul>
 */
public final class CampaignFeatureBuilder {

    /** "Velocidad reciente" = último 10 % de la duración ({@code RECENT_WINDOW_FRACTION} del entrenamiento). */
    static final double RECENT_WINDOW_FRACTION = 0.10;

    private CampaignFeatureBuilder() {}

    public static CampaignFeatures build(CampaignPredictionData data, Instant now) {
        double duration = days(data.startDate(), data.endDate());
        double target = data.targetAmount();
        double t = days(data.startDate(), now) / duration;

        List<IntentOutcome> past = data.intents().stream().filter(i -> i.outcome() != Outcome.IN_PROGRESS).toList();
        List<IntentOutcome> confirmed = past.stream().filter(i -> i.outcome() == Outcome.CONFIRMED).toList();

        double raised = 0;
        Set<Integer> donors = new HashSet<>();
        for (IntentOutcome i : confirmed) {
            raised += i.amount();
            donors.add(i.donorKey());
        }
        double pctRaised = raised / target;

        double windowStart = Math.max(0.0, t - RECENT_WINDOW_FRACTION);
        double recentAmount = 0;
        for (IntentOutcome i : confirmed) {
            if (i.confirmedAt() != null && days(data.startDate(), i.confirmedAt()) / duration >= windowStart) {
                recentAmount += i.amount();
            }
        }
        double windowDays = Math.max((t - windowStart) * duration, 1e-9);
        double elapsedDays = t * duration;
        double remainingDays = duration - elapsedDays;
        long failed = past.stream().filter(i -> i.outcome() == Outcome.FAILED).count();

        return new CampaignFeatures(
                Math.log(target),
                duration,
                data.orgPriorCampaigns(),
                data.paymentMethodsEnabled(),
                t,
                pctRaised,
                confirmed.size(),
                donors.size(),
                confirmed.isEmpty() ? 0.0 : (raised / confirmed.size()) / target,
                recentAmount / target / windowDays,
                pctRaised / Math.max(elapsedDays, 1e-9),
                Math.max(0.0, 1.0 - pctRaised) / Math.max(remainingDays, 1e-9),
                pctRaised / t,
                past.isEmpty() ? 0.0 : (double) failed / past.size(),
                data.targetPolicy(),
                data.visibility());
    }

    static double days(Instant from, Instant to) {
        return Duration.between(from, to).toNanos() / 86_400_000_000_000.0;
    }
}
