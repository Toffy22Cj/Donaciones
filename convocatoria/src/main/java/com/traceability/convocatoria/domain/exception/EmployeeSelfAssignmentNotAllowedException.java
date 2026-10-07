package com.traceability.convocatoria.domain.exception;

/**
 * Un `EMPLOYEE` nunca se autoasigna (Enmienda §4.3; implementation_plan.md §5, §6).
 */
public class EmployeeSelfAssignmentNotAllowedException extends ConvocatoriaDomainException {

    public EmployeeSelfAssignmentNotAllowedException(String message) {
        super(message);
    }
}
