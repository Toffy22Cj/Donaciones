package com.traceability.convocatoria.domain.exception;

/**
 * `startDate` o `endDate` anteriores a `now − 5 min` al crear (deuda D-2 de la ficha CV-01; Q-CV01-9a).
 */
public class CampaignDateInPastException extends ConvocatoriaDomainException {

    public CampaignDateInPastException(String message) {
        super(message);
    }
}
