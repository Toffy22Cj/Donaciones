package com.traceability.app.web.asset;

import com.traceability.api.web.CurrentActor;
import com.traceability.api.web.InvalidRequestFieldException;
import com.traceability.app.application.media.ImageTooLargeException;
import com.traceability.app.application.media.MediaUploadService;
import com.traceability.app.application.media.StepEvidenceIndex.Step;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.core.application.authorization.CommandType;
import com.traceability.core.application.authorization.OrganizationBoundaryPolicy;
import com.traceability.core.application.authorization.RoleAuthorizationPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Subida de la foto opcional de registrar, despachar o recibir (ADR-051 Enmienda 2, E2-1; plan de la Tarea 2, §2.1):
 * {@code POST /api/v1/organizations/{organizationId}/asset-evidence}, multipart con {@code file} y {@code step}
 * ({@code REGISTERED}, {@code DISPATCHED} o {@code RECEIVED}) → {@code 201 {evidenceRef}} ({@code sha256:}). Sin
 * {@code Command-Id}, como la de la entrega.
 * <p>
 * Orden: el {@code step} (400 si falta o no es uno de los tres: la entrega tiene su propia ruta, {@code delivery-evidence}),
 * después <b>autoriza antes de leer los bytes</b> con las mismas políticas de {@code core} que el comando de ese paso
 * ({@link OrganizationBoundaryPolicy} y {@link RoleAuthorizationPolicy}); el resto, el mismo 403 (DD-01). Los mismos bytes del
 * mismo propósito dentro de una organización dan la misma huella sin duplicar; en propósitos distintos, objetos separados.
 * Sin cambios de {@code core}: solo traduce (ADR-041 §2.1).
 */
@RestController
public class StepEvidenceController {

    public record EvidenceResponse(String evidenceRef) {}

    private final MediaUploadService uploads;
    private final OrganizationBoundaryPolicy boundary;
    private final RoleAuthorizationPolicy roles;

    public StepEvidenceController(MediaUploadService uploads, OrganizationBoundaryPolicy boundary,
                                  RoleAuthorizationPolicy roles) {
        this.uploads = uploads;
        this.boundary = boundary;
        this.roles = roles;
    }

    @PostMapping(path = "/api/v1/organizations/{organizationId}/asset-evidence",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EvidenceResponse> upload(@CurrentActor AuthorizationPrincipal principal,
                                                   @PathVariable("organizationId") String organizationId,
                                                   @RequestParam(value = "step", required = false) String step,
                                                   @RequestPart("file") MultipartFile file) {
        Step target = stepOf(step);
        boundary.assertBelongs(principal.organizationId(), organizationId);
        roles.authorize(principal, commandOf(target));
        if (file.getSize() > target.purpose().maxBytes()) {
            throw new ImageTooLargeException("Step evidence larger than the limit for its purpose");
        }
        String sha256 = uploads.store(target.purpose(), organizationId, null, principal.accountId(), bytes(file)).sha256();
        return ResponseEntity.status(HttpStatus.CREATED).body(new EvidenceResponse("sha256:" + sha256));
    }

    /** Solo los tres pasos opcionales, en mayúsculas (E2-1); {@code DELIVERED} y todo lo demás, 400. */
    private static Step stepOf(String value) {
        if ("REGISTERED".equals(value)) return Step.REGISTERED;
        if ("DISPATCHED".equals(value)) return Step.DISPATCHED;
        if ("RECEIVED".equals(value)) return Step.RECEIVED;
        throw new InvalidRequestFieldException("step");
    }

    /** El comando que autoriza ese paso (los tres exigen {@code EMPLOYEE}; registrar cubre los dos caminos). */
    private static CommandType commandOf(Step step) {
        return switch (step) {
            case REGISTERED -> CommandType.REGISTER_PHYSICAL_ASSET;
            case DISPATCHED -> CommandType.DISPATCH_PHYSICAL_ASSET;
            case RECEIVED -> CommandType.RECEIVE_PHYSICAL_ASSET;
            case DELIVERED -> throw new InvalidRequestFieldException("step");
        };
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
