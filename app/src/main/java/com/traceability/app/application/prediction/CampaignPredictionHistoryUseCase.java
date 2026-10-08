package com.traceability.app.application.prediction;

import com.traceability.app.application.prediction.CampaignPredictionUseCase.Unavailable;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.convocatoria.application.query.CampaignPredictionDataQuery;
import com.traceability.convocatoria.application.query.CampaignTimelineQuery;
import com.traceability.core.application.port.out.CampaignClearedFundsPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** Estimaciones históricas (encargo 6, P3, S-10). Esqueleto. */
@Service
public class CampaignPredictionHistoryUseCase {

    public static final List<Double> CUTS = List.of(0.15, 0.25, 0.50);
    public static final String FAILED_RATE_WARNING =
            "En los cortes pasados la tasa de fallos es 0: los pagos fallidos no están en el Event Store";

    /** {@code unavailable} es {@code null} si el corte tiene estimación. */
    public record Cut(double t, Instant cutAt, Unavailable unavailable, Double probabilityReachTarget,
                      Double estimatedFinalPctOfTarget, Double pctRaisedAtCut) {}

    /** {@code unavailable} (de toda la convocatoria) es {@code null} si hay cortes. */
    public record History(String modelVersion, Instant asOf, Unavailable unavailable, List<Cut> cuts,
                          List<String> warnings) {}

    public CampaignPredictionHistoryUseCase(CampaignPredictionDataQuery data, CampaignTimelineQuery timeline,
                                            CampaignClearedFundsPort clearedFunds, ObjectProvider<Clock> clock) {
    }

    public History history(AuthorizationPrincipal principal, String organizationId, String campaignRef) {
        throw new UnsupportedOperationException("pendiente");
    }
}
