package com.traceability.convocatoria.domain.exception;

/** No se quita MONETARY de una convocatoria que ya tiene intenciones de donación (Enmienda 4 de ADR-037, D4). */
public class MonetaryRemovalNotAllowedException extends ConvocatoriaDomainException {
    public MonetaryRemovalNotAllowedException(String message) {
        super(message);
    }
}
