package com.traceability.app.web.campaign;

import com.traceability.api.web.CurrentActor;
import com.traceability.app.application.media.CampaignCoverService;
import com.traceability.app.application.media.ImageTooLargeException;
import com.traceability.app.application.media.MediaPurpose;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * ADR-051, Tarea 3 (F8; plan de cierre §T3): subir y quitar la portada de una convocatoria, multipart con el campo
 * {@code file}. Solo el {@code ADMINISTRATOR} de la organización (C1 (a)); el resto, el mismo 403, antes de leer los
 * bytes. {@code PUT} → {@code 200 {coverImageUrl}}; {@code DELETE} → {@code 204}, también sin portada.
 */
@RestController
public class CampaignCoverController {

    public record CoverResponse(String coverImageUrl) {}

    private final CampaignCoverService covers;

    public CampaignCoverController(CampaignCoverService covers) {
        this.covers = covers;
    }

    @PutMapping(path = "/api/v1/organizations/{organizationId}/campaigns/{campaignRef}/cover",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CoverResponse replace(@CurrentActor AuthorizationPrincipal principal,
                                 @PathVariable("organizationId") String organizationId,
                                 @PathVariable("campaignRef") String campaignRef,
                                 @RequestPart("file") MultipartFile file) {
        covers.requireCoverManager(principal, organizationId, campaignRef);
        if (file.getSize() > MediaPurpose.CAMPAIGN_COVER.maxBytes()) {
            throw new ImageTooLargeException("Cover larger than the limit for its purpose");
        }
        return new CoverResponse(covers.replace(principal, organizationId, campaignRef, bytes(file)));
    }

    @DeleteMapping("/api/v1/organizations/{organizationId}/campaigns/{campaignRef}/cover")
    public ResponseEntity<Void> remove(@CurrentActor AuthorizationPrincipal principal,
                                       @PathVariable("organizationId") String organizationId,
                                       @PathVariable("campaignRef") String campaignRef) {
        covers.remove(principal, organizationId, campaignRef);
        return ResponseEntity.noContent().build();
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
