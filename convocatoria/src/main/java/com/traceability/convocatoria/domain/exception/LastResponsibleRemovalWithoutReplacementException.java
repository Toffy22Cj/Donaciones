package com.traceability.convocatoria.domain.exception;

/**
 * Retirar sin reemplazo dejaría la convocatoria sin responsables activos (ADR-037 §2.5).
 */
public class LastResponsibleRemovalWithoutReplacementException extends ConvocatoriaDomainException {

    public LastResponsibleRemovalWithoutReplacementException(String message) {
        super(message);
    }
}
