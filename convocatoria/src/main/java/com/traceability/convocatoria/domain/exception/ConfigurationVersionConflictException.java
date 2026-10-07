package com.traceability.convocatoria.domain.exception;

/**
 * La configuración ya avanzó respecto de la versión esperada: conflicto, nunca sobrescritura (Enmienda §3.2 [REQUISITO]; resumen §6.8.5).
 */
public class ConfigurationVersionConflictException extends ConvocatoriaDomainException {

    public ConfigurationVersionConflictException(String message) {
        super(message);
    }
}
