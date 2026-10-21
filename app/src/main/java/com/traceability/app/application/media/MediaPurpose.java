package com.traceability.app.application.media;

/**
 * Propósito de una imagen (ADR-051 F9, columna {@code purpose}) y su tamaño máximo en bytes (F8: 5 MB la evidencia,
 * 2 MB el logo y la portada). Los megabytes son binarios, como el {@code DataSize} de Spring que F8 usa para el
 * <em>multipart</em> ({@code 5MB} = 5 × 1024 × 1024).
 */
public enum MediaPurpose {

    ORGANIZATION_LOGO(2 * 1024 * 1024),
    CAMPAIGN_COVER(2 * 1024 * 1024),
    DELIVERY_EVIDENCE(5 * 1024 * 1024);

    private final int maxBytes;

    MediaPurpose(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    public int maxBytes() {
        return maxBytes;
    }
}
