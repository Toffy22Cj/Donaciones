package com.traceability.convocatoria.domain.exception;

/** La solicitud ya no está pendiente (Enmienda 4 de ADR-037, D2). */
public class ConfigurationChangeRequestNotPendingException extends ConvocatoriaDomainException {
    public ConfigurationChangeRequestNotPendingException(String message) {
        super(message);
    }
}
