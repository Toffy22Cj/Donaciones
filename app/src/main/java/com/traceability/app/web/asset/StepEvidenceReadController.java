package com.traceability.app.web.asset;

import com.traceability.api.web.CurrentActor;
import com.traceability.api.web.InvalidRequestFieldException;
import com.traceability.app.application.media.DeliveryEvidenceUnavailableException;
import com.traceability.app.application.media.EvidenceRemovedException;
import com.traceability.app.application.media.MediaObject;
import com.traceability.app.application.media.MediaObjectStore;
import com.traceability.app.application.media.StepEvidenceIndex;
import com.traceability.app.application.media.StepEvidenceIndex.Entry;
import com.traceability.app.application.media.StepEvidenceIndex.Step;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.PlatformAuthority;
import com.traceability.contracts.media.MediaStoragePort;
import com.traceability.core.application.authorization.OrganizationBoundaryPolicy;
import com.traceability.core.application.exception.PhysicalAssetNotFoundException;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.physicalasset.PhysicalAsset;
import identity.application.authorization.PlatformAuthorizationPolicy;
import identity.application.authorization.PlatformCommandType;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * Lectura de las fotos por paso (ADR-051 Enmienda 2, E2-6; plan de la Tarea 2, §2.3, Q-T2-1):
 * <ul>
 *   <li>{@code GET /api/v1/physical-assets/{assetRef}/evidence/{step}} con {@code step} en minúsculas ({@code registered},
 *   {@code dispatched}, {@code received}, {@code delivered}; {@code delivered} es un alias de la ruta de siempre). Da la
 *   foto del <b>último</b> evento de ese paso, o la de un evento concreto con {@code ?sequence=N}.</li>
 *   <li>{@code GET /api/v1/physical-assets/{assetRef}/evidence/steps}: los pasos del activo con {@code sequence}, fecha,
 *   {@code hasPhoto} y {@code removed}; <b>sin huellas ni claves</b>.</li>
 * </ul>
 * Misma autorización y respuestas que {@link DeliveryEvidenceReadController}: miembros de la organización con cualquier rol
 * y el administrador de plataforma; el resto, el mismo 403 (un activo inexistente, también, salvo para la plataforma, que
 * recibe 404); sin foto, el mismo 404; retirada por F7, 410; {@code private, no-store}. <b>Nunca en una ruta pública.</b>
 * La huella sale del evento de cada paso, no del estado del agregado (H-7).
 */
@RestController
public class StepEvidenceReadController {

    /** Una fila del listado. El orden de los campos es el del JSON. */
    public record StepView(String step, long sequence, String occurredAt, boolean hasPhoto, boolean removed) {}

    private final EventStorePort eventStore;
    private final OrganizationBoundaryPolicy boundary;
    private final PlatformAuthorizationPolicy platform;
    private final MediaObjectStore metadata;
    private final MediaStoragePort storage;

    public StepEvidenceReadController(EventStorePort eventStore, OrganizationBoundaryPolicy boundary,
                                      PlatformAuthorizationPolicy platform, MediaObjectStore metadata,
                                      MediaStoragePort storage) {
        this.eventStore = eventStore;
        this.boundary = boundary;
        this.platform = platform;
        this.metadata = metadata;
        this.storage = storage;
    }

    /** El activo y sus pasos, tras autorizar (antes de leer ningún byte de imagen). */
    private record Loaded(String organizationRef, List<Entry> entries) {}

    private Loaded load(AuthorizationPrincipal principal, String assetRef) {
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
        return new Loaded(asset.getOrganizationRef(), StepEvidenceIndex.of(events));
    }

    @GetMapping("/api/v1/physical-assets/{assetRef}/evidence/steps")
    public ResponseEntity<List<StepView>> steps(@CurrentActor AuthorizationPrincipal principal,
                                                @PathVariable("assetRef") String assetRef) {
        Loaded loaded = load(principal, assetRef);
        List<StepView> views = loaded.entries().stream().map(e -> new StepView(e.step().routeName(), e.sequence(),
                e.occurredAt() == null ? null : e.occurredAt().toString(), e.evidenceRef() != null,
                e.evidenceRef() != null && find(loaded.organizationRef(), e).filter(o -> o.deletedAt() != null).isPresent())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(views);
    }

    @GetMapping("/api/v1/physical-assets/{assetRef}/evidence/{step}")
    public ResponseEntity<byte[]> read(@CurrentActor AuthorizationPrincipal principal,
                                       @PathVariable("assetRef") String assetRef,
                                       @PathVariable("step") String step,
                                       @RequestParam(value = "sequence", required = false) String sequence) {
        Loaded loaded = load(principal, assetRef);
        Step target = Step.fromRoute(step).orElseThrow(DeliveryEvidenceUnavailableException::new);
        Optional<Entry> entry;
        if (sequence == null) {
            entry = StepEvidenceIndex.latest(loaded.entries(), target);
        } else {
            entry = StepEvidenceIndex.at(loaded.entries(), target, parseSequence(sequence));
        }
        Entry chosen = entry.filter(e -> e.evidenceRef() != null).orElseThrow(DeliveryEvidenceUnavailableException::new);
        MediaObject object = find(loaded.organizationRef(), chosen).orElseThrow(DeliveryEvidenceUnavailableException::new);
        if (object.deletedAt() != null) {
            throw new EvidenceRemovedException();
        }
        byte[] content = storage.get(object.objectKey()).orElseThrow(DeliveryEvidenceUnavailableException::new);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .contentType(MediaType.parseMediaType(object.contentType())).body(content);
    }

    private Optional<MediaObject> find(String organizationRef, Entry entry) {
        return metadata.findByOrganizationIdAndPurposeAndSha256(organizationRef, entry.step().purpose(),
                entry.evidenceRef().substring("sha256:".length()));
    }

    private static long parseSequence(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1) {
                throw new InvalidRequestFieldException("sequence");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new InvalidRequestFieldException("sequence");
        }
    }
}
