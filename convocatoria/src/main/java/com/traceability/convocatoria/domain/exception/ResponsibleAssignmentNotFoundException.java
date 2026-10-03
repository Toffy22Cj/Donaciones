package com.traceability.convocatoria.domain.exception;

/**
 * No existe asignación activa del responsable en la convocatoria (ADR-037 §2.5; Enmienda §4.2).
 */
public class ResponsibleAssignmentNotFoundException extends ConvocatoriaDomainException {

    public ResponsibleAssignmentNotFoundException(String message) {
        super(message);
    }
}
