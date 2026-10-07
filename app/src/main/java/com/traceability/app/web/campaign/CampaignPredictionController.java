package com.traceability.app.web.campaign;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.traceability.api.web.CurrentActor;
import com.traceability.app.application.prediction.CampaignPredictionUseCase;
import com.traceability.app.application.prediction.CampaignPredictionUseCase.Prediction;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Predicción de una convocatoria (P3), de solo lectura. Siempre {@code kind: "ESTIMATE"} con la versión del modelo y la
 * advertencia de datos sintéticos; sin estimación, {@code available: false} con el motivo (p. ej. STRICT).
 */
@RestController
public class CampaignPredictionController {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PredictionResponse(String kind, String modelVersion, String warning, boolean available,
                                     String unavailableReason, String unavailableText, Double probabilityReachTarget,
                                     Double estimatedFinalPctOfTarget, Double pctTimeElapsed, List<String> warnings,
                                     String asOf) {}

    private final CampaignPredictionUseCase predictions;

    public CampaignPredictionController(CampaignPredictionUseCase predictions) {
        this.predictions = predictions;
    }

    @GetMapping("/api/v1/organizations/{organizationId}/campaigns/{campaignRef}/prediction")
    public ResponseEntity<PredictionResponse> prediction(@CurrentActor AuthorizationPrincipal principal,
                                                         @PathVariable("organizationId") String organizationId,
                                                         @PathVariable("campaignRef") String campaignRef) {
        Prediction p = predictions.predict(principal, organizationId, campaignRef);
        PredictionResponse body = new PredictionResponse("ESTIMATE", p.modelVersion(),
                CampaignPredictionUseCase.SYNTHETIC_WARNING, p.unavailable() == null,
                p.unavailable() == null ? null : p.unavailable().name(),
                p.unavailable() == null ? null : p.unavailable().text,
                p.probabilityReachTarget(), p.estimatedFinalPctOfTarget(), p.pctTimeElapsed(), p.warnings(),
                p.asOf().toString());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
