package com.traceability.app.application.media;

/**
 * Ya existe una evidencia de entrega con los mismos bytes en la organización (ADR-051 F9: índice único
 * {@code (organizationId, purpose, sha256)} para la evidencia). La lanza el adaptador de {@link MediaObjectStore} al
 * perder una carrera; {@link MediaUploadService} la resuelve devolviendo la evidencia ganadora.
 */
public class DuplicateEvidenceException extends RuntimeException {

    public DuplicateEvidenceException(String message) {
        super(message);
    }
}
