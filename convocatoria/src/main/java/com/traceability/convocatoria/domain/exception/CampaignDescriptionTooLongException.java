package com.traceability.convocatoria.domain.exception;

/**
 * `description` supera los 5000 caracteres (deuda D-9 de la ficha CV-01; Q-CV01-15).
 */
public class CampaignDescriptionTooLongException extends ConvocatoriaDomainException {

    public CampaignDescriptionTooLongException(String message) {
        super(message);
    }
}
