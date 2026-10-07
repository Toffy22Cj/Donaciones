package com.traceability.app.web.campaign;

import com.traceability.ai.domain.narrative.CampaignNarrative;
import com.traceability.app.application.campaign.PublicCampaignNarrativeUseCase;
import com.traceability.app.application.campaign.PublicCampaignNarrativeUseCase.PublicFacts;
import com.traceability.app.application.campaign.PublicCampaignNarrativeUseCase.PublicNarrative;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Narrativa pública de una convocatoria (ADR-040; plan B5 §4), ruta ya pública en {@code PublicRoutes}. {@code 200}
 * con {@code AVAILABLE} o {@code UNAVAILABLE} ("Narrativa no disponible"), {@code 202} con {@code PENDING} mientras se
 * genera. Un código inexistente da el mismo 404 que CV-07. El código no se registra ni se devuelve.
 */
@RestController
public class PublicCampaignNarrativeController {

    public record NarrativeResponse(String status, String content, String source, PublicFacts facts) {}

    private final PublicCampaignNarrativeUseCase narratives;

    public PublicCampaignNarrativeController(PublicCampaignNarrativeUseCase narratives) {
        this.narratives = narratives;
    }

    @GetMapping("/api/v1/public/campaigns/{publicCode}/narrative")
    public ResponseEntity<NarrativeResponse> narrative(@PathVariable("publicCode") String publicCode) {
        PublicNarrative result = narratives.narrativeOf(publicCode).orElseThrow(PublicCampaignNotFoundException::new);
        CampaignNarrative n = result.narrative();
        NarrativeResponse body = new NarrativeResponse(n.status().name(), n.content(),
                n.source() == null ? null : n.source().name(), result.facts());
        return ResponseEntity.status(n.status() == CampaignNarrative.Status.PENDING ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(body);
    }
}
