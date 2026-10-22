package com.traceability.app.web.campaign;

import com.traceability.app.application.media.OrganizationLogoService;
import com.traceability.app.application.media.OrganizationLogoService.Logo;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;


/**
 * {@code GET /api/v1/public/campaigns/{publicCode}/organization-logo} (ADR-051 F6, Enmienda 1; plan de la Tarea 2
 * §4.3), sin JWT: el logo de la organización dueña de la convocatoria, con sus mismas reglas de visibilidad.
 * {@code Cache-Control: public, max-age=3600} y {@code ETag} con la huella, entre comillas. Sin {@code If-None-Match}
 * (P-T2-9). Nunca el {@code organizationId}. El código es un secreto bearer: no se registra.
 */
@RestController
public class PublicOrganizationLogoController {

    private final OrganizationLogoService logos;

    public PublicOrganizationLogoController(OrganizationLogoService logos) {
        this.logos = logos;
    }

    @GetMapping("/api/v1/public/campaigns/{publicCode}/organization-logo")
    public ResponseEntity<byte[]> read(@PathVariable("publicCode") String publicCode) {
        Logo logo = logos.publicLogo(publicCode);
        // el texto literal de F6, en ese orden
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .eTag("\"" + logo.sha256() + "\"")
                .contentType(MediaType.parseMediaType(logo.contentType())).body(logo.content());
    }
}
