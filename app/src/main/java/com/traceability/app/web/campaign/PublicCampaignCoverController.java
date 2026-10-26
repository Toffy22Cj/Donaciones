package com.traceability.app.web.campaign;

import com.traceability.app.application.media.CampaignCoverService;
import com.traceability.app.application.media.CampaignCoverService.Cover;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-051, Tarea 3 (F6): {@code GET /api/v1/public/campaigns/{publicCode}/cover}, sin JWT, con las mismas cabeceras
 * que el logo público. Convocatoria no visible por su código, sin portada, retirada o sin bytes → el mismo 404.
 */
@RestController
public class PublicCampaignCoverController {

    private final CampaignCoverService covers;

    public PublicCampaignCoverController(CampaignCoverService covers) {
        this.covers = covers;
    }

    @GetMapping("/api/v1/public/campaigns/{publicCode}/cover")
    public ResponseEntity<byte[]> read(@PathVariable("publicCode") String publicCode) {
        Cover cover = covers.publicCover(publicCode);
        // el texto literal de F6, en ese orden
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .eTag("\"" + cover.sha256() + "\"")
                .contentType(MediaType.parseMediaType(cover.contentType())).body(cover.content());
    }
}
