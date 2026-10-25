package com.traceability.app.web.asset;

import com.traceability.api.web.CurrentActor;
import com.traceability.app.application.media.ImageTooLargeException;
import com.traceability.app.application.media.MediaPurpose;
import com.traceability.app.application.media.MediaUploadService;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.core.application.authorization.CommandType;
import com.traceability.core.application.authorization.OrganizationBoundaryPolicy;
import com.traceability.core.application.authorization.RoleAuthorizationPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Subida de la evidencia de entrega (ADR-051 F2, F4 y F8; plan de la Tarea 4, §3):
 * {@code POST /api/v1/organizations/{organizationId}/delivery-evidence}, multipart con el campo {@code file} →
 * {@code 201 {evidenceRef}}, con {@code evidenceRef = "sha256:" + SHA-256 de los bytes limpios} (F3, F4).
 * <p>
 * Autoriza <b>antes de leer los bytes</b>, con las mismas políticas de {@code core} que la entrega (P-T4-4): principal de
 * esa organización ({@link OrganizationBoundaryPolicy}) con un rol que pueda entregar ({@link RoleAuthorizationPolicy},
 * {@code DELIVER_ASSET}). El resto, el mismo 403 (DD-01). La ruta no tiene activo: la entrega vuelve a comprobar que la
 * foto es de la organización del activo (F3). Los mismos bytes de la misma organización dan el mismo {@code evidenceRef}
 * sin duplicar (F9). Solo traduce (ADR-041 §2.1).
 */
@RestController
public class DeliveryEvidenceController {

    public record EvidenceResponse(String evidenceRef) {}

    private final MediaUploadService uploads;
    private final OrganizationBoundaryPolicy boundary;
    private final RoleAuthorizationPolicy roles;

    public DeliveryEvidenceController(MediaUploadService uploads, OrganizationBoundaryPolicy boundary,
                                      RoleAuthorizationPolicy roles) {
        this.uploads = uploads;
        this.boundary = boundary;
        this.roles = roles;
    }

    @PostMapping(path = "/api/v1/organizations/{organizationId}/delivery-evidence",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EvidenceResponse> upload(@CurrentActor AuthorizationPrincipal principal,
                                                   @PathVariable("organizationId") String organizationId,
                                                   @RequestPart("file") MultipartFile file) {
        boundary.assertBelongs(principal.organizationId(), organizationId);
        roles.authorize(principal, CommandType.DELIVER_ASSET);
        if (file.getSize() > MediaPurpose.DELIVERY_EVIDENCE.maxBytes()) {
            throw new ImageTooLargeException("Delivery evidence larger than the limit for its purpose");
        }
        String sha256 = uploads.store(MediaPurpose.DELIVERY_EVIDENCE, organizationId, null, principal.accountId(),
                bytes(file)).sha256();
        return ResponseEntity.status(HttpStatus.CREATED).body(new EvidenceResponse("sha256:" + sha256));
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
