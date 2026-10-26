package com.traceability.app.web.platform;

import com.traceability.api.web.CurrentActor;
import com.traceability.api.web.InvalidRequestFieldException;
import com.traceability.app.application.media.MediaObject;
import com.traceability.app.application.media.MediaObjectStore;
import com.traceability.app.application.media.MediaPurpose;
import com.traceability.app.application.media.MediaRemovalTargetNotFoundException;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.media.MediaStoragePort;
import com.traceability.convocatoria.application.service.ConvocatoriaCoverReferenceService;
import identity.application.authorization.PlatformAuthorizationPolicy;
import identity.application.authorization.PlatformCommandType;
import identity.application.service.OrganizationLogoReferenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * ADR-051 F7 (Tarea 5; plan de cierre §T5, P-T5-2 y P-T5-3):
 * {@code POST /api/v1/platform/organizations/{organizationId}/media/remove} con {@code {purpose, ref, reason}}. Solo el
 * administrador de plataforma ({@code REMOVE_MEDIA}), que se comprueba antes de validar el cuerpo; el resto, el mismo
 * 403. Borra los bytes del proveedor y deja en el metadato {@code deletedAt}, {@code deletedBy} y {@code reason}; el
 * metadato no se borra.
 * <ul>
 *   <li>{@code DELIVERY_EVIDENCE}: {@code ref} es el {@code evidenceRef}; inexistente en esa organización → 404. La
 *   huella sigue en el evento, que es inmutable.</li>
 *   <li>{@code ORGANIZATION_LOGO}: sin {@code ref}; quita además la referencia en {@code identity}.</li>
 *   <li>{@code CAMPAIGN_COVER}: {@code ref} es el {@code campaignRef}; quita además la referencia en
 *   {@code convocatoria}.</li>
 * </ul>
 * Algo ya retirado, o sin logo ni portada → 204 igual. {@code reason} (1 a 500 caracteres) solo se guarda en el
 * metadato: nunca va al log ni a una respuesta.
 */
@RestController
public class PlatformMediaRemovalController {

    public record RemovalRequest(String purpose, String ref, String reason) {}

    static final int MAX_REASON = 500;
    private static final Pattern FOOTPRINT = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Logger log = LoggerFactory.getLogger(PlatformMediaRemovalController.class);

    private final PlatformAuthorizationPolicy platform;
    private final MediaObjectStore metadata;
    private final MediaStoragePort storage;
    private final OrganizationLogoReferenceService logos;
    private final ConvocatoriaCoverReferenceService covers;
    private final Clock clock;

    public PlatformMediaRemovalController(PlatformAuthorizationPolicy platform, MediaObjectStore metadata,
                                          MediaStoragePort storage, OrganizationLogoReferenceService logos,
                                          ConvocatoriaCoverReferenceService covers, ObjectProvider<Clock> clock) {
        this.platform = platform;
        this.metadata = metadata;
        this.storage = storage;
        this.logos = logos;
        this.covers = covers;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    @PostMapping("/api/v1/platform/organizations/{organizationId}/media/remove")
    public ResponseEntity<Void> remove(@CurrentActor AuthorizationPrincipal principal,
                                       @PathVariable("organizationId") String organizationId,
                                       @RequestBody(required = false) RemovalRequest request) {
        platform.authorize(principal, PlatformCommandType.REMOVE_MEDIA);
        if (request == null) {
            throw new InvalidRequestFieldException("purpose");
        }
        MediaPurpose purpose = purpose(request.purpose());
        String reason = request.reason() == null ? null : request.reason().trim();
        if (!StringUtils.hasText(reason) || reason.length() > MAX_REASON) {
            throw new InvalidRequestFieldException("reason");
        }
        Optional<String> objectKey = switch (purpose) {
            case DELIVERY_EVIDENCE -> evidenceKey(organizationId, request.ref());
            case ORGANIZATION_LOGO -> logos.removeAsPlatform(principal, organizationId);
            case CAMPAIGN_COVER -> {
                if (!StringUtils.hasText(request.ref())) {
                    throw new InvalidRequestFieldException("ref");
                }
                yield covers.removeAsPlatform(principal.accountId(), organizationId, request.ref());
            }
        };
        objectKey.ifPresent(key -> discard(key, principal.accountId(), reason));
        return ResponseEntity.noContent().build();
    }

    private static MediaPurpose purpose(String value) {
        try {
            return MediaPurpose.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidRequestFieldException("purpose");
        }
    }

    /** La evidencia de esa organización con esa huella; ya retirada → vacío (P-T5-3). */
    private Optional<String> evidenceKey(String organizationId, String evidenceRef) {
        if (evidenceRef == null || !FOOTPRINT.matcher(evidenceRef).matches()) {
            throw new InvalidRequestFieldException("ref");
        }
        MediaObject object = metadata.findByOrganizationIdAndPurposeAndSha256(organizationId,
                        MediaPurpose.DELIVERY_EVIDENCE, evidenceRef.substring("sha256:".length()))
                .orElseThrow(MediaRemovalTargetNotFoundException::new);
        return object.deletedAt() == null ? Optional.of(object.objectKey()) : Optional.empty();
    }

    /** F7: primero los bytes; si el proveedor falla, 503 y el metadato sin marcar, para poder reintentar. */
    private void discard(String objectKey, String removedBy, String reason) {
        try {
            storage.delete(objectKey);
        } catch (RuntimeException e) {
            // la clave y la clase de la causa, como F2; nunca el motivo
            log.warn("media.removal.failed objectKey={} cause={}", objectKey, e.getClass().getSimpleName());
            throw e;
        }
        metadata.markDeleted(objectKey, removedBy, clock.instant(), reason);
    }
}
