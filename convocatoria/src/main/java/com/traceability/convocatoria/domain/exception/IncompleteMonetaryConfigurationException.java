package com.traceability.convocatoria.domain.exception;

/**
 * Con `MONETARY`, `acceptedPaymentMethods` no vacío y `targetAmount`/`targetPolicy` definidos (Enmienda §3.1; resumen §6.8.4).
 */
public class IncompleteMonetaryConfigurationException extends ConvocatoriaDomainException {

    public IncompleteMonetaryConfigurationException(String message) {
        super(message);
    }
}
