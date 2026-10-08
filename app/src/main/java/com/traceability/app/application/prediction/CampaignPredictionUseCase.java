package com.traceability.app.application.prediction;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.convocatoria.application.query.CampaignPredictionData;
import com.traceability.convocatoria.application.query.CampaignPredictionDataQuery;
import com.traceability.convocatoria.domain.exception.ActorNotInCampaignOrganizationException;
import com.traceability.convocatoria.domain.exception.ActorRoleNotAllowedException;
import com.traceability.convocatoria.domain.exception.CampaignNotFoundException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Predicción de una convocatoria (segunda autorización de Carlos, P3; borrador de enmienda de ADR-044). Solo lectura:
 * nunca escribe en el event store, en las proyecciones ni en {@code convocatoria}. Solo para {@code ADMINISTRATOR} o
 * {@code REPRESENTATIVE} de la organización de la convocatoria, comprobado en el servidor; otra organización, una
 * convocatoria inexistente o de otra organización y un rol insuficiente dan el mismo 403 (DD-01).
 */
@Service
public class CampaignPredictionUseCase {

    public static final String MODEL_RESOURCE = "/predictor/campaign-predictor-baseline-0.2.0.json";
    public static final String SYNTHETIC_WARNING = "modelo entrenado con datos sintéticos";
    /** El dataset sintético de {@code baseline-0.2.0} está en COP (Carlos, 2026-10-07: motivo explícito para el resto). */
    static final String SUPPORTED_CURRENCY = "COP";
    static final double TRAINED_T_MIN = 0.15;
    static final double TRAINED_T_MAX = 0.50;

    public enum Unavailable {
        STRICT_POLICY_EXCLUDED("La política STRICT rechaza el exceso sobre la meta; el modelo no se entrenó con ella"),
        NO_MONETARY_TARGET("La convocatoria no tiene meta monetaria"),
        UNSUPPORTED_CURRENCY("El modelo solo se entrenó con convocatorias en COP"),
        NOT_STARTED("La convocatoria aún no ha empezado"),
        CAMPAIGN_ENDED("La convocatoria ya terminó"),
        TARGET_ALREADY_REACHED("La meta ya se alcanzó"),
        TOO_MANY_INTENTS("Demasiadas intenciones para calcular la estimación"),
        /** Carlos, 2026-10-08: fuera del rango de entrenamiento, ninguna cifra. */
        OUTSIDE_TRAINED_RANGE("Fuera del rango del modelo: solo estima entre el 15 % y el 50 % del tiempo de la convocatoria");

        public final String text;

        Unavailable(String text) {
            this.text = text;
        }
    }

    /** {@code unavailable} es {@code null} si hay estimación. */
    public record Prediction(String modelVersion, Instant asOf, Unavailable unavailable, Double probabilityReachTarget,
                             Double estimatedFinalPctOfTarget, Double pctTimeElapsed, List<String> warnings) {}

    private final CampaignPredictionDataQuery data;
    private final CampaignPredictorModel model;
    private final Clock clock;

    public CampaignPredictionUseCase(CampaignPredictionDataQuery data, ObjectProvider<Clock> clock) {
        this.data = data;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        try (InputStream json = CampaignPredictionUseCase.class.getResourceAsStream(MODEL_RESOURCE)) {
            if (json == null) {
                throw new IllegalStateException("Falta el recurso del predictor " + MODEL_RESOURCE);
            }
            this.model = CampaignPredictorModel.load(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Prediction predict(AuthorizationPrincipal principal, String organizationId, String campaignRef) {
        if (principal.organizationId() == null || !principal.organizationId().equals(organizationId)) {
            throw new ActorNotInCampaignOrganizationException("Actor not in organization");
        }
        if (principal.roles() == null || !(principal.roles().contains(AuthorizationRole.ADMINISTRATOR)
                || principal.roles().contains(AuthorizationRole.REPRESENTATIVE))) {
            throw new ActorRoleNotAllowedException("Prediction requires ADMINISTRATOR or REPRESENTATIVE");
        }
        CampaignPredictionData d = data.dataOf(campaignRef)
                .filter(c -> c.organizationRef().equals(organizationId))
                .orElseThrow(() -> new CampaignNotFoundException("Campaign not found"));

        Instant now = clock.instant();
        if (d.targetAmount() == null) {
            return unavailable(now, Unavailable.NO_MONETARY_TARGET);
        }
        if (!SUPPORTED_CURRENCY.equals(d.currency())) {
            return unavailable(now, Unavailable.UNSUPPORTED_CURRENCY);
        }
        if (model.excludedPolicies().contains(d.targetPolicy())) {
            return unavailable(now, Unavailable.STRICT_POLICY_EXCLUDED);
        }
        if (!now.isAfter(d.startDate())) {
            return unavailable(now, Unavailable.NOT_STARTED);
        }
        if ("CLOSED".equals(d.status()) || !now.isBefore(d.endDate())) {
            return unavailable(now, Unavailable.CAMPAIGN_ENDED);
        }
        if (d.truncated()) {
            return unavailable(now, Unavailable.TOO_MANY_INTENTS);
        }
        CampaignFeatures features = CampaignFeatureBuilder.build(d, now);
        if (features.pctRaised() >= 1.0) {
            return unavailable(now, Unavailable.TARGET_ALREADY_REACHED);
        }
        // Carlos, 2026-10-08: fuera del rango de entrenamiento no se da ninguna cifra, solo el motivo
        if (features.pctTimeElapsed() < TRAINED_T_MIN || features.pctTimeElapsed() > TRAINED_T_MAX) {
            return unavailable(now, Unavailable.OUTSIDE_TRAINED_RANGE);
        }
        Map<String, Object> x = features.byName();
        return new Prediction(model.modelVersion(), now, null, round(model.probabilityReachTarget(x)),
                round(model.finalPctOfTarget(x)), round(features.pctTimeElapsed()), List.of(SYNTHETIC_WARNING));
    }

    private Prediction unavailable(Instant now, Unavailable reason) {
        return new Prediction(model.modelVersion(), now, reason, null, null, null, List.of(SYNTHETIC_WARNING));
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
