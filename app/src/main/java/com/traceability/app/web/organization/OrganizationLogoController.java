package com.traceability.app.web.organization;

import com.traceability.api.web.CurrentActor;
import com.traceability.app.application.media.ImageTooLargeException;
import com.traceability.app.application.media.MediaPurpose;
import com.traceability.app.application.media.OrganizationLogoService;
import com.traceability.app.application.media.OrganizationLogoService.Logo;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Logo de la organización (ADR-051 F6, F8 y Enmienda 1; plan de la Tarea 2 §4.2 y §4.3):
 * <ul>
 *   <li>{@code PUT …/logo} (multipart, campo {@code file}) → {@code 200 {logoUrl}}. {@code REPRESENTATIVE} o
 *   {@code ADMINISTRATOR}; el permiso se comprueba antes que el tamaño, y el tamaño (2 MB, P-1) antes de llamar a la
 *   subida;</li>
 *   <li>{@code DELETE …/logo} → {@code 204}, también sin logo previo (P-T2-4);</li>
 *   <li>{@code GET …/logo}: miembro de la organización o administrador de plataforma, {@code private, no-store}.</li>
 * </ul>
 * El resto, el mismo 403 (DD-01). Solo traduce (ADR-041 §2.1).
 */
@RestController
public class OrganizationLogoController {

    public record LogoResponse(String logoUrl) {}

    private final OrganizationLogoService logos;

    public OrganizationLogoController(OrganizationLogoService logos) {
        this.logos = logos;
    }

    @PutMapping(path = "/api/v1/organizations/{organizationId}/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public LogoResponse replace(@CurrentActor AuthorizationPrincipal principal,
                                @PathVariable("organizationId") String organizationId,
                                @RequestPart("file") MultipartFile file) {
        logos.requireLogoManager(principal, organizationId);
        if (file.getSize() > MediaPurpose.ORGANIZATION_LOGO.maxBytes()) {
            throw new ImageTooLargeException("Logo larger than the limit for its purpose");
        }
        return new LogoResponse(logos.replace(principal, organizationId, bytes(file)));
    }

    @DeleteMapping("/api/v1/organizations/{organizationId}/logo")
    public ResponseEntity<Void> remove(@CurrentActor AuthorizationPrincipal principal,
                                       @PathVariable("organizationId") String organizationId) {
        logos.remove(principal, organizationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/organizations/{organizationId}/logo")
    public ResponseEntity<byte[]> read(@CurrentActor AuthorizationPrincipal principal,
                                       @PathVariable("organizationId") String organizationId) {
        Logo logo = logos.memberLogo(principal, organizationId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .contentType(MediaType.parseMediaType(logo.contentType())).body(logo.content());
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
