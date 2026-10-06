package com.traceability.convocatoria.domain.exception;

/**
 * El actor no tiene el rol exigido por la operación (ADR-037 §5; Enmienda §3.4, §4.1; implementation_plan.md §6).
 */
public class ActorRoleNotAllowedException extends ConvocatoriaDomainException {

    public ActorRoleNotAllowedException(String message) {
        super(message);
    }
}
