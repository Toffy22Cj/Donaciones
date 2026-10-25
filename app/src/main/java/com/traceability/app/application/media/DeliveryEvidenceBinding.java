package com.traceability.app.application.media;

import com.traceability.contracts.media.DeliveryEvidencePort;
import com.traceability.contracts.media.EvidenceAlreadyBoundException;
import com.traceability.contracts.media.EvidenceNotFoundException;
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
        if (organizationId == null || evidenceRef == null || !evidenceRef.startsWith(PREFIX)) {
            throw new EvidenceNotFoundException("Delivery evidence not found");
        }
        switch (store.bindEvidence(organizationId, evidenceRef.substring(PREFIX.length()), assetId)) {
            case BOUND -> { }
            case BOUND_TO_OTHER -> throw new EvidenceAlreadyBoundException("Delivery evidence already bound to another asset");
            case NOT_FOUND -> throw new EvidenceNotFoundException("Delivery evidence not found");
        }
    }
}
