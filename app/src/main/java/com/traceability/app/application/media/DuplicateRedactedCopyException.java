package com.traceability.app.application.media;

/**
 * Ya existe la copia censurada de esa original (ADR-051 Enmienda 2, E2-4b; plan de la Tarea 3, §2.2): índice único
 * {@code (organizationId, purpose, ownerRef)} de {@code DELIVERY_EVIDENCE_REDACTED}. Significa «ya existe», nunca un error:
 * quien pierde la carrera relee la copia ganadora y la devuelve. No es una {@link DuplicateEvidenceException}: la copia no
 * tiene índice por huella (dos originales distintas pueden dar copias idénticas).
 */
public class DuplicateRedactedCopyException extends RuntimeException {

    public DuplicateRedactedCopyException(String message) {
        super(message);
    }
}
