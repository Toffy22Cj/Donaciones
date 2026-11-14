package com.traceability.api.web.ratelimit;

/** ADR-053 D4: configuración del límite de intentos ausente o inválida; la aplicación no arranca. */
public class RateLimitConfigurationException extends IllegalStateException {
    public RateLimitConfigurationException(String message) {
        super(message);
    }
}
