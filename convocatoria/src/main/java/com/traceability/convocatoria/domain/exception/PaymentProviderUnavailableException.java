package com.traceability.convocatoria.domain.exception;

/**
 * Intención `GATEWAY` sin proveedor de pago configurado (Enmienda 3 de ADR-037, D3; fuera de `dev`/`demo` no hay ninguno).
 */
public class PaymentProviderUnavailableException extends ConvocatoriaDomainException {

    public PaymentProviderUnavailableException(String message) {
        super(message);
    }
}
