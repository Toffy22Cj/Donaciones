package com.traceability.convocatoria.domain.exception;

/**
 * El evento del proveedor no coincide con la intención en proveedor, importe o moneda (Enmienda 3 de ADR-037, D1).
 */
public class PaymentEventMismatchException extends ConvocatoriaDomainException {

    public PaymentEventMismatchException(String message) {
        super(message);
    }
}
