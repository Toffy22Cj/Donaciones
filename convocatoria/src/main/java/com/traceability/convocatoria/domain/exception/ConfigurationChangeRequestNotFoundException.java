package com.traceability.convocatoria.domain.exception;

/** Solicitud inexistente o de otra convocatoria: el mismo 403 que un recurso ajeno (DD-01). */
public class ConfigurationChangeRequestNotFoundException extends ConvocatoriaDomainException {
    public ConfigurationChangeRequestNotFoundException(String message) {
        super(message);
    }
}
