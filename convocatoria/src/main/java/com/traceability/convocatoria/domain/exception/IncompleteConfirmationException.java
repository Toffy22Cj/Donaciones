package com.traceability.convocatoria.domain.exception;

/**
 * Toda confirmación registra quién, cuándo, medio y referencia/evidencia (N8, Enmienda §5.2).
 */
public class IncompleteConfirmationException extends ConvocatoriaDomainException {

    public IncompleteConfirmationException(String message) {
        super(message);
    }
}
