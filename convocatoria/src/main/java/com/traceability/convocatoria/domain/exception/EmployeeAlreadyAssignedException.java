package com.traceability.convocatoria.domain.exception;

/**
 * El `EMPLOYEE` ya es responsable activo de una convocatoria (ADR-037 §2.4; Enmienda §4.2).
 */
public class EmployeeAlreadyAssignedException extends ConvocatoriaDomainException {

    public EmployeeAlreadyAssignedException(String message) {
        super(message);
    }
}
