package com.traceability.app.application.media;

/**
 * Lo que devuelve una subida (ADR-051 F4: "la subida devuelve el {@code evidenceRef}"; F9): la clave en el proveedor y
 * la huella de los bytes guardados, sin exponer el documento de {@code media_objects}.
 */
public record StoredMedia(String objectKey, String sha256, String contentType, long sizeBytes, int width, int height) {
}
