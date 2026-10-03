package com.traceability.convocatoria.domain.exception;

/**
 * `onTargetReached` presente si y solo si `targetPolicy = CLOSE_ON_TARGET` (Enmienda §3.1; implementation_plan.md §3.1).
 */
public class InvalidOnTargetReachedException extends ConvocatoriaDomainException {

    public InvalidOnTargetReachedException(String message) {
        super(message);
    }
}
