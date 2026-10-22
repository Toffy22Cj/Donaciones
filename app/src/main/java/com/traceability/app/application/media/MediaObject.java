package com.traceability.app.application.media;

import java.time.Instant;

/**
 * Metadatos de una imagen guardada, en la capa de aplicación (ADR-051 F9): lo que {@link MediaUploadService} inserta y
 * consulta a través de {@link MediaObjectStore}, sin conocer el documento de Mongo. {@code deletedAt} es nulo mientras
 * la imagen no se haya borrado (F10; plan de la Tarea 2, D-T2-2).
 */
public record MediaObject(String objectKey, String sha256, MediaPurpose purpose, String organizationId, String ownerRef,
                          String contentType, long sizeBytes, int width, int height, String uploadedBy,
                          Instant uploadedAt, Instant deletedAt) {

    /** Una imagen sin borrar: los 11 campos de la Tarea 1 (D-T2-2). */
    public MediaObject(String objectKey, String sha256, MediaPurpose purpose, String organizationId, String ownerRef,
                       String contentType, long sizeBytes, int width, int height, String uploadedBy,
                       Instant uploadedAt) {
        this(objectKey, sha256, purpose, organizationId, ownerRef, contentType, sizeBytes, width, height, uploadedBy,
                uploadedAt, null);
    }
}
