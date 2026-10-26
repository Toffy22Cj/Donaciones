package com.traceability.app.web.asset;

import com.traceability.api.web.CurrentActor;
import com.traceability.app.application.media.DeliveryEvidenceUnavailableException;
import com.traceability.app.application.media.EvidenceRemovedException;
import com.traceability.app.application.media.MediaObject;
import com.traceability.app.application.media.MediaObjectStore;
import com.traceability.app.application.media.MediaPurpose;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.PlatformAuthority;
import com.traceability.contracts.media.MediaStoragePort;
import com.traceability.core.application.authorization.OrganizationBoundaryPolicy;
import com.traceability.core.application.exception.PhysicalAssetNotFoundException;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.physicalasset.PhysicalAsset;
import com.traceability.core.domain.physicalasset.payloads.AssetDeliveredPayload;
import identity.application.authorization.PlatformAuthorizationPolicy;
import identity.application.authorization.PlatformCommandType;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.regex.Pattern;

/**
 * ADR-051 F6 (Tarea 5; plan de cierre §T5): {@code GET /api/v1/physical-assets/{assetRef}/evidence}, la foto de la
 * entrega de un activo, con {@code private, no-store}.
 * <ul>
 *   <li>La ven los miembros de la organización del activo, con cualquier rol, y el administrador de plataforma
 *   ({@code READ_DELIVERY_EVIDENCE}). El resto, el mismo 403; un activo inexistente, también (DD-12), salvo para la
 *   plataforma, que recibe 404.</li>
 *   <li>Sin entrega, con un {@code evidenceRef} heredado de texto libre, sin metadato o sin bytes → el mismo 404.</li>
 *   <li>Retirada por la plataforma (F7) → 410 {@code EvidenceRemoved}.</li>
 * </ul>
 * La organización y el {@code evidenceRef} salen del propio activo en el Event Store; antes de autorizar no se lee
 * ningún byte.
 */
@RestController
public class DeliveryEvidenceReadController {

    private static final Pattern FOOTPRINT = Pattern.compile("^sha256:[0-9a-f]{64}$");

    private final EventStorePort eventStore;
    private final OrganizationBoundaryPolicy boundary;
    private final PlatformAuthorizationPolicy platform;
    private final MediaObjectStore metadata;
    private final MediaStoragePort storage;

    public DeliveryEvidenceReadController(EventStorePort eventStore, OrganizationBoundaryPolicy boundary,
                                          PlatformAuthorizationPolicy platform, MediaObjectStore metadata,
                                          MediaStoragePort storage) {
        this.eventStore = eventStore;
        this.boundary = boundary;
        this.platform = platform;
        this.metadata = metadata;
        this.storage = storage;
    }

    @GetMapping("/api/v1/physical-assets/{assetRef}/evidence")
    public ResponseEntity<byte[]> read(@CurrentActor AuthorizationPrincipal principal,
                                       @PathVariable("assetRef") String assetRef) {
        boolean asPlatform = principal.platformAuthority() == PlatformAuthority.ADMINISTRATOR;
        if (asPlatform) {
            platform.authorize(principal, PlatformCommandType.READ_DELIVERY_EVIDENCE);
        }
        List<DomainEvent> events = eventStore.loadStream(assetRef);
        if (events.isEmpty()) {
            if (asPlatform) {
                throw new DeliveryEvidenceUnavailableException();
            }
            throw new PhysicalAssetNotFoundException(assetRef);
        }
        PhysicalAsset asset = PhysicalAsset.rehydrate(assetRef, events.stream().map(DomainEvent::payload).toList(),
                events.size());
        if (!asPlatform) {
            boundary.assertBelongs(principal.organizationId(), asset.getOrganizationRef());
        }
        String evidenceRef = events.stream().map(DomainEvent::payload)
                .filter(AssetDeliveredPayload.class::isInstance).map(p -> ((AssetDeliveredPayload) p).evidenceRef())
                .reduce((first, second) -> second).orElse(null);
        if (evidenceRef == null || !FOOTPRINT.matcher(evidenceRef).matches()) {
            throw new DeliveryEvidenceUnavailableException();
        }
        MediaObject object = metadata.findByOrganizationIdAndPurposeAndSha256(asset.getOrganizationRef(),
                        MediaPurpose.DELIVERY_EVIDENCE, evidenceRef.substring("sha256:".length()))
                .orElseThrow(DeliveryEvidenceUnavailableException::new);
        if (object.deletedAt() != null) {
            throw new EvidenceRemovedException();
        }
        byte[] content = storage.get(object.objectKey()).orElseThrow(DeliveryEvidenceUnavailableException::new);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .contentType(MediaType.parseMediaType(object.contentType())).body(content);
    }
}
