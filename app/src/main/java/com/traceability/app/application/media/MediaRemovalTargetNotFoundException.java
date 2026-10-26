package com.traceability.app.application.media;

/** ADR-051 F7 (Tarea 5): la evidencia que la plataforma quiere retirar no existe en esa organización. 404, solo para la plataforma. */
public class MediaRemovalTargetNotFoundException extends RuntimeException {
    public MediaRemovalTargetNotFoundException() {
        super("Media removal target NotFound");
    }
}
