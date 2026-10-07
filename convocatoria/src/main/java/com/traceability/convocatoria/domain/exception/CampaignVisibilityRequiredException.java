package com.traceability.convocatoria.domain.exception;

/**
 * `visibility` ausente al crear (deuda D-8 de la ficha CV-01; Q-CV01-14).
 */
public class CampaignVisibilityRequiredException extends ConvocatoriaDomainException {

    public CampaignVisibilityRequiredException(String message) {
        super(message);
    }
}
