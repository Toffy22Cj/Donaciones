package com.traceability.convocatoria.domain.exception;

/**
 * `paymentMethod` no aceptado en la versión vigente (N6, Enmienda §5.1).
 */
public class PaymentMethodNotAcceptedException extends ConvocatoriaDomainException {

    public PaymentMethodNotAcceptedException(String message) {
        super(message);
    }
}
