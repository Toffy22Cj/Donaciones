package com.traceability.core.application.saga;

/**
 * Fallo permanente de una saga (ADR-007/008 Enmienda 1, D2): el dominio hace imposible la operación. Cualquier otra
 * excepción de una política se trata como transitoria.
 */
public class PermanentSagaFailureException extends RuntimeException {

    public PermanentSagaFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
