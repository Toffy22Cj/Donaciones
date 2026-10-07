package com.traceability.convocatoria.domain.exception;

/** Ya hay una solicitud pendiente para la convocatoria (Enmienda 4 de ADR-037, D2). */
public class ConfigurationChangeRequestAlreadyPendingException extends ConvocatoriaDomainException {
    public ConfigurationChangeRequestAlreadyPendingException(String message) {
        super(message);
    }
}
