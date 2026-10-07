package com.traceability.convocatoria.domain.exception;

/**
 * El importe de la intención debe ser estrictamente positivo (ADR-037 §2.6; condición de fallo nombrada según regla 2.6).
 */
public class InvalidDonationAmountException extends ConvocatoriaDomainException {

    public InvalidDonationAmountException(String message) {
        super(message);
    }
}
