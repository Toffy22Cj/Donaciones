package com.traceability.convocatoria.domain.exception;

/**
 * `currency` no es un código ISO 4217 de tres letras mayúsculas reconocido (deuda D-1 de la ficha CV-01; Q-CV01-3).
 */
public class InvalidCampaignCurrencyException extends ConvocatoriaDomainException {

    public InvalidCampaignCurrencyException(String message) {
        super(message);
    }
}
