package com.traceability.convocatoria.domain.exception;

/**
 * Una convocatoria `CLOSED` no admite cambios de configuración (Enmienda §3.2, §3.4).
 */
public class ConfigurationChangeOnClosedCampaignException extends ConvocatoriaDomainException {

    public ConfigurationChangeOnClosedCampaignException(String message) {
        super(message);
    }
}
