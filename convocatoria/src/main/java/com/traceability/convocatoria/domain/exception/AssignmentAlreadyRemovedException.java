package com.traceability.convocatoria.domain.exception;

/**
 * `REMOVED` es histórico y no se reactiva (ADR-037 §2.4, §7; Enmienda §4.2).
 */
public class AssignmentAlreadyRemovedException extends ConvocatoriaDomainException {

    public AssignmentAlreadyRemovedException(String message) {
        super(message);
    }
}
