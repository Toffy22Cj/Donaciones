package com.traceability.convocatoria.domain.exception;

/**
 * Ningún `DonationIntent` tiene ese `paymentSessionId` (Enmienda 3 de ADR-037, D1).
 */
public class PaymentCorrelationNotFoundException extends ConvocatoriaDomainException {

    public PaymentCorrelationNotFoundException(String message) {
        super(message);
    }
}
