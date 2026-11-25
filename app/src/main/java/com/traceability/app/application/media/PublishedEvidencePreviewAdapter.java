package com.traceability.app.application.media;

import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.PublishedEvidencePreviewPort;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.physicalasset.PhysicalAsset;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * La copia censurada de la foto de entrega para el seguimiento público (ADR-021-D Enmienda 2, E5 a E7; plan de la Tarea 4).
 * Solo lee: no genera la copia ni cambia su publicación. Devuelve algo <b>solo</b> si se cumplen las tres condiciones
 * (la copia existe, está publicada y la original no está retirada); en cualquier otro caso devuelve vacío sin decir cuál
 * falló (E7), y nunca expone quién publicó ni ninguna clave de objeto.
 */
@Component
public class PublishedEvidencePreviewAdapter implements PublishedEvidencePreviewPort {

    private static final Pattern FOOTPRINT = Pattern.compile("^sha256:[0-9a-f]{64}$");

    private final EventStorePort eventStore;
    private final MediaObjectStore metadata;
    private final RedactedEvidenceService redacted;

    public PublishedEvidencePreviewAdapter(EventStorePort eventStore, MediaObjectStore metadata, RedactedEvidenceService redacted) {
        this.eventStore = eventStore;
        this.metadata = metadata;
        this.redacted = redacted;
    }

    @Override
    public boolean hasPublishedPreview(String assetId) {
        return original(assetId).map(o -> redacted.publication(o).published()).orElse(false);
    }

    @Override
    public Optional<byte[]> findPublishedPreview(String assetId) {
        Optional<MediaObject> original = original(assetId);
        if (original.isEmpty()) {
            return Optional.empty();
        }
        try {
            return redacted.publishedCopy(original.get()).map(RedactedEvidenceService.Copy::bytes);
        } catch (DeliveryEvidenceUnavailableException | EvidenceRemovedException e) {
            // los bytes ya no están o la copia se retiró entre dos lecturas: para el seguimiento es lo mismo que «no hay»
            return Optional.empty();
        }
    }

    /** La foto original de la entrega del activo (la huella del evento), viva o retirada; vacío si no hay foto. */
    private Optional<MediaObject> original(String assetId) {
        List<DomainEvent> events = eventStore.loadStream(assetId);
        if (events.isEmpty()) {
            return Optional.empty();
        }
        PhysicalAsset asset = PhysicalAsset.rehydrate(assetId, events.stream().map(DomainEvent::payload).toList(), events.size());
        String evidenceRef = asset.getFinalEvidenceRef();
        if (evidenceRef == null || !FOOTPRINT.matcher(evidenceRef).matches()) {
            return Optional.empty();
        }
        return metadata.findByOrganizationIdAndPurposeAndSha256(asset.getOrganizationRef(), MediaPurpose.DELIVERY_EVIDENCE,
                evidenceRef.substring("sha256:".length()));
    }
}
