package com.traceability.app.application.media;

import com.traceability.contracts.media.DeliveryEvidencePort;
import com.traceability.contracts.media.EvidenceAlreadyBoundException;
import com.traceability.contracts.media.EvidenceNotFoundException;
import com.traceability.contracts.media.EvidenceStep;
import org.springframework.stereotype.Component;

/**
 * Implementación de {@link DeliveryEvidencePort} sobre {@code media_objects} (ADR-051 F3; plan de la Tarea 4, §3): quita
 * el prefijo {@code sha256:}, hace la escritura condicional de {@link MediaObjectStore#bindEvidence} y traduce su
 * resultado a las dos excepciones del puerto.
 */
@Component
public class DeliveryEvidenceBinding implements DeliveryEvidencePort {

    static final String PREFIX = "sha256:";

    private final MediaObjectStore store;

    public DeliveryEvidenceBinding(MediaObjectStore store) {
        this.store = store;
    }

    @Override
    public void bindToAsset(String organizationId, String evidenceRef, String assetId) {
        bind(organizationId, evidenceRef, assetId, MediaPurpose.DELIVERY_EVIDENCE, "Delivery evidence");
    }

    /** ADR-051 Enmienda 2, E2-1: cada paso vincula la foto de su propio propósito. */
    @Override
    public void bindToAsset(String organizationId, String evidenceRef, String assetId, EvidenceStep step) {
        bind(organizationId, evidenceRef, assetId, switch (step) {
            case REGISTERED -> MediaPurpose.REGISTRATION_EVIDENCE;
            case DISPATCHED -> MediaPurpose.DISPATCH_EVIDENCE;
            case RECEIVED -> MediaPurpose.RECEIPT_EVIDENCE;
        }, "Step evidence");
    }

    private void bind(String organizationId, String evidenceRef, String assetId, MediaPurpose purpose, String what) {
        if (organizationId == null || evidenceRef == null || !evidenceRef.startsWith(PREFIX)) {
            throw new EvidenceNotFoundException(what + " not found");
        }
        // la entrega usa el método de tres argumentos del almacén, que no cambia; los demás pasos, el del propósito
        MediaObjectStore.BindResult result = purpose == MediaPurpose.DELIVERY_EVIDENCE
                ? store.bindEvidence(organizationId, evidenceRef.substring(PREFIX.length()), assetId)
                : store.bindEvidence(organizationId, evidenceRef.substring(PREFIX.length()), assetId, purpose);
        switch (result) {
            case BOUND -> { }
            case BOUND_TO_OTHER -> throw new EvidenceAlreadyBoundException(what + " already bound to another asset");
            case NOT_FOUND -> throw new EvidenceNotFoundException(what + " not found");
        }
    }
}
