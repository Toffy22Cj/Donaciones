package com.traceability.contracts.media;

/**
 * El paso del activo al que pertenece una foto de evidencia opcional (ADR-051 Enmienda 2, E2-1): registrar, despachar o
 * recibir. Cada paso tiene su propio propósito en {@code media_objects}, así que una foto subida para un paso no existe
 * para otro. La entrega no usa este tipo: su vínculo es {@link DeliveryEvidencePort#bindToAsset(String, String, String)}.
 */
public enum EvidenceStep {
    REGISTERED,
    DISPATCHED,
    RECEIVED
}
