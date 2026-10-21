package com.traceability.app.application.media;

import java.time.Instant;

/**
 * Metadatos de una imagen guardada, en la capa de aplicación (ADR-051 F9): lo que {@link MediaUploadService} inserta y
 * consulta a través de {@link MediaObjectStore}, sin conocer el documento de Mongo.
 */
public record MediaObject(String objectKey, String sha256, MediaPurpose purpose, String organizationId, String ownerRef,
                          String contentType, long sizeBytes, int width, int height, String uploadedBy,
                          Instant uploadedAt) {
}
