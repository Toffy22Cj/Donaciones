package com.traceability.app.application.prediction;

import com.traceability.app.application.prediction.CampaignPredictionUseCase.Unavailable;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.convocatoria.application.query.CampaignPredictionData;
import com.traceability.convocatoria.application.query.CampaignPredictionData.IntentOutcome;
import com.traceability.convocatoria.application.query.CampaignPredictionData.Outcome;
import com.traceability.convocatoria.application.query.CampaignPredictionDataQuery;
import com.traceability.convocatoria.application.query.CampaignTimelineQuery;
import com.traceability.convocatoria.application.query.CampaignTimelineQuery.CampaignTimeline;
import com.traceability.core.application.port.out.CampaignClearedFundsPort;
import com.traceability.core.application.port.out.CampaignClearedFundsPort.ClearedFund;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Estimaciones históricas de una convocatoria (encargo 6, P3; solicitud S-10 del gráfico avanzado): la estimación en
 * t = 0,15, 0,25 y 0,50 de los cortes ya pasados, con los mismos modelos y reglas que {@link CampaignPredictionUseCase}
 * ({@code kind: "ESTIMATE"}, sin STRICT, solo COP, solo {@code ADMINISTRATOR}/{@code REPRESENTATIVE}). Solo lectura.
 * <p>
 * Lo recaudado en cada corte se reconstruye desde el <b>event store</b>: los {@code FUNDS_CLEARED} de la convocatoria
 * con {@code occurredAt} anterior al corte (estrictamente, como {@code dayFraction < t} del entrenamiento). Nunca se
 * usa el estado actual de las intenciones. Decisiones ({@code [DECISIÓN DELEGADA — pendiente de ratificar]} DD-75):
 * <ul>
 *   <li>los pagos fallidos no están en el event store y las intenciones no guardan cuándo fallaron: en un corte pasado
 *   la tasa de fallos es 0, y la respuesta lo advierte;</li>
 *   <li>un corte futuro, uno posterior al cierre o uno anterior a un cambio de configuración (cuyos valores de entonces
 *   no se conocen) no llevan cifra, sino el motivo;</li>
 *   <li>un corte con la meta ya alcanzada da lo recaudado ({@code pctRaisedAtCut}) pero no una estimación, porque el
 *   entrenamiento excluye esas instantáneas.</li>
 * </ul>
 */
@Service
public class CampaignPredictionHistoryUseCase {

    /** Los puntos de evaluación del entrenamiento (ADR-044 D3). */
    public static final List<Double> CUTS = List.of(0.15, 0.25, 0.50);
    public static final String FAILED_RATE_WARNING =
            "En los cortes pasados la tasa de fallos es 0: los pagos fallidos no están en el Event Store";

    /** {@code unavailable} es {@code null} si el corte tiene estimación. */
    public record Cut(double t, Instant cutAt, Unavailable unavailable, Double probabilityReachTarget,
                      Double estimatedFinalPctOfTarget, Double pctRaisedAtCut) {}

    /** {@code unavailable} (de toda la convocatoria) es {@code null} si hay cortes. */
    public record History(String modelVersion, Instant asOf, Unavailable unavailable, List<Cut> cuts,
                          List<String> warnings) {}

    private final CampaignPredictionDataQuery data;
    private final CampaignTimelineQuery timeline;
    private final CampaignClearedFundsPort clearedFunds;
    private final CampaignPredictorModel model;
    private final Clock clock;

    public CampaignPredictionHistoryUseCase(CampaignPredictionDataQuery data, CampaignTimelineQuery timeline,
                                            CampaignClearedFundsPort clearedFunds, ObjectProvider<Clock> clock) {
        this.data = data;
        this.timeline = timeline;
        this.clearedFunds = clearedFunds;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.model = CampaignPredictionUseCase.loadModel();
    }

    public History history(AuthorizationPrincipal principal, String organizationId, String campaignRef) {
        CampaignPredictionData d = CampaignPredictionUseCase.authorizedData(data, principal, organizationId, campaignRef);
        Instant now = clock.instant();
        if (d.targetAmount() == null) {
            return unavailable(now, Unavailable.NO_MONETARY_TARGET);
        }
        if (!CampaignPredictionUseCase.SUPPORTED_CURRENCY.equals(d.currency())) {
            return unavailable(now, Unavailable.UNSUPPORTED_CURRENCY);
        }
        if (model.excludedPolicies().contains(d.targetPolicy())) {
            return unavailable(now, Unavailable.STRICT_POLICY_EXCLUDED);
        }
        List<ClearedFund> funds = clearedFunds.findClearedFundsByCampaign(campaignRef,
                CampaignPredictionDataQuery.MAX_INTENTS + 1);
        if (funds.size() > CampaignPredictionDataQuery.MAX_INTENTS) {
            return unavailable(now, Unavailable.TOO_MANY_INTENTS);
        }
        CampaignTimeline line = timeline.timelineOf(campaignRef);
        long durationNanos = Duration.between(d.startDate(), d.endDate()).toNanos();
        List<Cut> cuts = new ArrayList<>();
        for (double t : CUTS) {
            Instant cutAt = d.startDate().plusNanos(Math.round(t * durationNanos));
            cuts.add(cut(d, funds, line, now, t, cutAt));
        }
        return new History(model.modelVersion(), now, null, List.copyOf(cuts),
                List.of(CampaignPredictionUseCase.SYNTHETIC_WARNING, FAILED_RATE_WARNING));
    }

    private Cut cut(CampaignPredictionData d, List<ClearedFund> funds, CampaignTimeline line, Instant now, double t,
                    Instant cutAt) {
        if (cutAt.isAfter(now)) {
            return new Cut(t, cutAt, Unavailable.FUTURE_CUT, null, null, null);
        }
        if (line.closedAt() != null && !line.closedAt().isAfter(cutAt)) {
            return new Cut(t, cutAt, Unavailable.CAMPAIGN_ENDED, null, null, null);
        }
        if (line.configurationChangedAt().stream().anyMatch(changedAt -> changedAt.isAfter(cutAt))) {
            return new Cut(t, cutAt, Unavailable.CONFIGURATION_CHANGED_AFTER_CUT, null, null, null);
        }
        CampaignFeatures features = CampaignFeatureBuilder.build(atCut(d, funds, cutAt), cutAt);
        Double pctRaised = CampaignPredictionUseCase.round(features.pctRaised());
        if (features.pctRaised() >= 1.0) {
            return new Cut(t, cutAt, Unavailable.TARGET_ALREADY_REACHED, null, null, pctRaised);
        }
        Map<String, Object> x = features.byName();
        return new Cut(t, cutAt, null, CampaignPredictionUseCase.round(model.probabilityReachTarget(x)),
                CampaignPredictionUseCase.round(model.finalPctOfTarget(x)), pctRaised);
    }

    /** La convocatoria con solo lo que el event store había acreditado antes del corte. */
    private static CampaignPredictionData atCut(CampaignPredictionData d, List<ClearedFund> funds, Instant cutAt) {
        Map<String, Integer> donorKeys = new HashMap<>();
        List<IntentOutcome> confirmed = new ArrayList<>();
        for (ClearedFund f : funds) {
            if (f.occurredAt().isBefore(cutAt)) {
                int donorKey = donorKeys.computeIfAbsent(String.valueOf(f.donorRef()), k -> donorKeys.size());
                confirmed.add(new IntentOutcome(Outcome.CONFIRMED, f.clearedAmount(), donorKey, f.occurredAt()));
            }
        }
        return new CampaignPredictionData(d.campaignRef(), d.organizationRef(), d.status(), d.visibility(),
                d.targetPolicy(), d.targetAmount(), d.currency(), d.startDate(), d.endDate(), d.paymentMethodsEnabled(),
                d.orgPriorCampaigns(), List.copyOf(confirmed), false);
    }

    private History unavailable(Instant now, Unavailable reason) {
        return new History(model.modelVersion(), now, reason, List.of(),
                List.of(CampaignPredictionUseCase.SYNTHETIC_WARNING));
    }
}
