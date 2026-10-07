package com.traceability.convocatoria.domain.exception;

/**
 * El destinatario no pertenece a la organización de la convocatoria o no tiene el rol exigido (X2, implementation_plan.md §11).
 */
public class InvalidResponsibleRecipientException extends ConvocatoriaDomainException {

    public InvalidResponsibleRecipientException(String message) {
        super(message);
    }
}
