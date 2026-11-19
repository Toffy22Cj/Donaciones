package com.traceability.app.application.media;

/**
 * Propósito de una imagen (ADR-051 F9, columna {@code purpose}) y su tamaño máximo en bytes (F8: 5 MB la evidencia,
 * 2 MB el logo y la portada). Los megabytes son binarios, como el {@code DataSize} de Spring que F8 usa para el
 * <em>multipart</em> ({@code 5MB} = 5 × 1024 × 1024).
 */
public enum MediaPurpose {

    ORGANIZATION_LOGO(2 * 1024 * 1024),
    CAMPAIGN_COVER(2 * 1024 * 1024),
    DELIVERY_EVIDENCE(5 * 1024 * 1024),
    // ADR-051 Enmienda 2, E2-1: la foto opcional de registrar, despachar y recibir; mismas reglas que la entrega (5 MB)
    REGISTRATION_EVIDENCE(5 * 1024 * 1024),
    DISPATCH_EVIDENCE(5 * 1024 * 1024),
    RECEIPT_EVIDENCE(5 * 1024 * 1024),
    // ADR-051 Enmienda 2, E2-4: la copia pixelada de la foto de entrega (un objeto aparte; su ownerRef es la objectKey de la original)
    DELIVERY_EVIDENCE_REDACTED(5 * 1024 * 1024);

    private final int maxBytes;

    MediaPurpose(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    public int maxBytes() {
        return maxBytes;
    }

    /** Las fotos de evidencia (la entrega y los tres pasos opcionales): se deduplican por huella dentro de la organización. */
    public boolean isEvidence() {
        return this == DELIVERY_EVIDENCE || this == REGISTRATION_EVIDENCE || this == DISPATCH_EVIDENCE || this == RECEIPT_EVIDENCE;
    }
}
