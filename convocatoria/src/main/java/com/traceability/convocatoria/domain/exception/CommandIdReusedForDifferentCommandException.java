package com.traceability.convocatoria.domain.exception;

/**
 * Un `commandId` ya usado por otro tipo de comando (I1, implementation_plan.md §7.1).
 */
public class CommandIdReusedForDifferentCommandException extends ConvocatoriaDomainException {

    public CommandIdReusedForDifferentCommandException(String message) {
        super(message);
    }
}
